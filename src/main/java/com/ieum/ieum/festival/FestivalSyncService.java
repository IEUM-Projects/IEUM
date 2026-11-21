package com.ieum.ieum.festival;

import com.ieum.ieum.region.GeoService;
import com.ieum.ieum.region.VWorldClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 축제 데이터 동기화 (FES-01)
 *
 * 하루 한 번(기본 새벽 4시 30분) 두 API에서 축제를 받아 festivals 표를 최신으로 맞춘다.
 *
 * 정리 기준
 *  1) 작년 1월 1일 이후에 끝나는 축제만 저장 (더 오래된 기록은 버림)
 *  2) 종료일이 시작일보다 빠른 잘못된 행은 저장하지 않고 로그만 남김
 *  3) 같은 축제(정리한 이름 + 시작일)는 하나로 합침
 *     - 표준데이터 안의 중복: 정보가 더 많은 쪽을 기본으로, 빈 칸은 다른 쪽에서 채움
 *     - 관광공사에도 있는 축제: 표준데이터 내용 + 관광공사 사진·좌표
 *  4) 좌표가 없으면 브이월드 주소 검색으로 채움, 지역 코드는 주소 글자 → 브이월드 순으로 정함 (가게와 같은 regions 코드, DATA-05)
 *  5) 두 API를 모두 받은 경우에만, 원본에서 사라진 축제를 지움 (한쪽이 실패한 날 데이터가 사라지지 않게)
 */
@Slf4j
@Service
public class FestivalSyncService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PublicDataClient publicData;
    private final GeoService geo;
    private final FestivalRepository repository;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final String onStartup;
    private final int tourDetailLimit;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public FestivalSyncService(PublicDataClient publicData, GeoService geo, FestivalRepository repository,
                               PlatformTransactionManager transactionManager,
                               @Value("${festival.sync.enabled:true}") boolean enabled,
                               @Value("${festival.sync.on-startup:empty}") String onStartup,
                               @Value("${festival.sync.tour-detail-limit:200}") int tourDetailLimit) {
        this.publicData = publicData;
        this.geo = geo;
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.enabled = enabled;
        this.onStartup = onStartup;
        this.tourDetailLimit = tourDetailLimit;
    }

    /** 결과 요약 (로그와 테스트용) */
    public record Result(boolean standardOk, boolean tourOk, int standardRows, int tourRows, int saved,
                         int skippedOld, int skippedBadDate, int mergedDuplicates, int deleted,
                         int geocoded, int geocodeFailed, int regioned, int tourDetails) {
    }

    /** 서버가 켜졌을 때: 표가 비어 있으면(기본값) 바로 한 번 받아 온다. 서버 시작을 막지 않게 따로 실행 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled || "never".equals(onStartup)) return;
        if ("always".equals(onStartup) || repository.count() == 0) {
            Thread.ofVirtual().name("festival-sync").start(this::syncSafely);
        } else {
            // 받기는 새벽에 하고, 좌표·지역이 빈 축제만 지금 채운다 (DATA-05: 23번 SQL로 카카오 좌표를 지운 뒤 등)
            Thread.ofVirtual().name("festival-locations").start(() -> {
                if (!running.compareAndSet(false, true)) return;
                try {
                    Counter c = new Counter();
                    locations(c);
                    log.info("축제 좌표·지역 채우기: 좌표 {}곳, 못 찾음 {}곳, 지역 {}곳", c.geocoded, c.geocodeFailed, c.regioned);
                } catch (RuntimeException e) {
                    log.error("축제 좌표·지역 채우기 중 오류", e);
                } finally {
                    running.set(false);
                }
            });
        }
    }

    @Scheduled(cron = "${festival.sync.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        if (enabled) syncSafely();
    }

    private void syncSafely() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.error("축제 동기화 중 오류", e);
        }
    }

    /** 한 번 동기화. 이미 실행 중이면 아무것도 하지 않고 빈 값 */
    public Optional<Result> sync() {
        if (!running.compareAndSet(false, true)) {
            log.info("축제 동기화가 이미 실행 중이에요.");
            return Optional.empty();
        }
        try {
            return Optional.of(run());
        } finally {
            running.set(false);
        }
    }

    private Result run() {
        LocalDateTime runStart = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = runStart.toLocalDate();
        LocalDate since = LocalDate.of(today.getYear() - 1, 1, 1);
        log.info("축제 동기화 시작 ({} 이후 축제)", since);

        // 1) 받기 (실패해도 다른 쪽은 계속)
        List<FestivalRecord> standard = List.of();
        List<FestivalRecord> tour = List.of();
        boolean standardOk = false;
        boolean tourOk = false;
        try {
            standard = publicData.fetchStandard();
            standardOk = true;
        } catch (PublicDataClient.PublicDataException e) {
            log.warn("표준데이터를 받지 못했어요: {}", e.getMessage());
        }
        try {
            tour = publicData.fetchTour(since);
            tourOk = true;
        } catch (PublicDataClient.PublicDataException e) {
            log.warn("관광공사 행사를 받지 못했어요: {}", e.getMessage());
        }

        // 2) 정리
        Counter c = new Counter();
        Map<String, FestivalRecord> standardByKey = dedupe(valid(standard, since, c), c);
        Map<String, FestivalRecord> tourByKey = dedupe(valid(tour, since, c), c);
        Map<String, FestivalRecord> merged = new LinkedHashMap<>(standardByKey);
        tourByKey.forEach((key, t) -> {
            FestivalRecord s = merged.get(key);
            merged.put(key, s == null ? t : s.mergeTour(t));
        });

        // 3) 저장 (한 번에)
        boolean bothOk = standardOk && tourOk;
        int[] deleted = {0};
        if (!merged.isEmpty() || bothOk) {
            tx.executeWithoutResult(status -> {
                Map<String, Festival> existing = repository.findAll().stream()
                        .collect(Collectors.toMap(Festival::getDedupeKey, Function.identity()));
                List<Festival> created = new ArrayList<>();
                merged.forEach((key, record) -> {
                    Festival f = existing.get(key);
                    if (f == null) {
                        f = Festival.create(key);
                        created.add(f);
                    } else if (!bothOk && !f.getSource().equals(record.source())) {
                        // 한쪽 API만 받은 날: 두 곳의 정보를 합쳐 둔 축제를 반쪽 정보로 덮어쓰지 않는다
                        return;
                    }
                    f.applyFrom(record, runStart);
                });
                repository.saveAll(created);
                if (bothOk) {
                    repository.flush();
                    deleted[0] = repository.deleteNotSeenSince(runStart);
                }
            });
        }

        // 4) 좌표·지역·소개글 채우기 (하나씩 저장. 중간에 실패해도 앞의 결과는 남는다)
        enrich(today, c);

        Result result = new Result(standardOk, tourOk, standard.size(), tour.size(), merged.size(),
                c.skippedOld, c.skippedBadDate, c.duplicates, deleted[0],
                c.geocoded, c.geocodeFailed, c.regioned, c.tourDetails);
        log.info("축제 동기화 끝: {}", result);
        return result;
    }

    private static class Counter {
        int skippedOld;
        int skippedBadDate;
        int duplicates;
        int geocoded;
        int geocodeFailed;
        int regioned;
        int tourDetails;
    }

    private List<FestivalRecord> valid(List<FestivalRecord> records, LocalDate since, Counter c) {
        List<FestivalRecord> result = new ArrayList<>();
        for (FestivalRecord r : records) {
            if (r.name() == null || r.startDate() == null || r.endDate() == null) {
                c.skippedBadDate++;
                log.debug("이름이나 날짜가 없어서 건너뜀: {}", r.name());
            } else if (r.endDate().isBefore(r.startDate())) {
                c.skippedBadDate++;
                log.info("종료일이 시작일보다 빨라서 건너뜀: {} ({} ~ {}, {})", r.name(), r.startDate(), r.endDate(), r.provider());
            } else if (r.endDate().isBefore(since)) {
                c.skippedOld++;
            } else {
                result.add(r);
            }
        }
        return result;
    }

    private Map<String, FestivalRecord> dedupe(List<FestivalRecord> records, Counter c) {
        Map<String, FestivalRecord> byKey = new HashMap<>();
        for (FestivalRecord r : records) {
            byKey.merge(r.dedupeKey(), r, (a, b) -> {
                c.duplicates++;
                // 정보가 더 많은 쪽을 기본으로 하고, 빈 칸은 다른 쪽에서 채운다
                return a.richness() >= b.richness() ? a.fillFrom(b) : b.fillFrom(a);
            });
        }
        // 날짜 순으로 넣어 두면 새로 만들어지는 id도 대체로 날짜 순이 된다
        return byKey.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(FestivalRecord::startDate)
                        .thenComparing(FestivalRecord::name)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    /** 좌표 없는 축제 → 브이월드 좌표, 지역 코드 없는 축제 → 지역 (DATA-05) */
    private void locations(Counter c) {
        try {
            if (!geo.available()) {
                log.warn("vworld.api-key가 없어서 좌표 없는 축제의 좌표를 채우지 못했어요.");
            } else {
                for (Festival f : repository.findNeedingGeocode()) {
                    Optional<GeoService.Located> point = Optional.empty();
                    try {
                        if (f.getRoadAddress() != null) point = geo.locate(f.getRoadAddress());
                        if (point.isEmpty() && f.getJibunAddress() != null) point = geo.locate(f.getJibunAddress());
                    } catch (VWorldClient.VWorldException e) {
                        if (!e.isRetryable()) throw e;
                        log.warn("축제 {} 좌표 찾기를 건너뜀 (다음에 다시): {}", f.getId(), e.getMessage());
                        continue;   // 잠깐의 오류: 이 축제만 다음에 다시
                    }
                    if (point.isPresent()) {
                        f.geocoded(point.get().lat(), point.get().lng());
                        if (point.get().sigunguCode() != null) f.region(point.get().sigunguCode());
                        c.geocoded++;
                    } else {
                        f.geocodeFailed();
                        c.geocodeFailed++;
                    }
                    repository.save(f);
                }
            }
            for (Festival f : repository.findNeedingRegion()) {
                Optional<String> code;
                try {
                    code = geo.regionOf(f.address(), f.getLatitude().doubleValue(), f.getLongitude().doubleValue());
                } catch (VWorldClient.VWorldException e) {
                    if (!e.isRetryable()) throw e;
                    continue;
                }
                if (code.isPresent()) {
                    f.region(code.get());
                    repository.save(f);
                    c.regioned++;
                }
            }
        } catch (VWorldClient.VWorldException e) {
            log.warn("브이월드 주소 검색을 쓸 수 없어 이번에는 좌표·지역 채우기를 멈춰요: {}", e.getMessage());
        }
    }

    private void enrich(LocalDate today, Counter c) {
        locations(c);

        if (publicData.hasKey() && tourDetailLimit > 0) {
            try {
                for (Festival f : repository.findNeedingTourDetail(today, PageRequest.of(0, tourDetailLimit))) {
                    PublicDataClient.TourDetail d = publicData.tourDetail(f.getTourContentId())
                            .orElse(new PublicDataClient.TourDetail(null, null, null));
                    f.tourDetail(d.overview(), d.homepage(), d.tel(), LocalDateTime.now(SEOUL));
                    repository.save(f);
                    c.tourDetails++;
                }
            } catch (PublicDataClient.PublicDataException e) {
                log.warn("관광공사 상세를 더 받지 못했어요 (다음 동기화 때 이어서): {}", e.getMessage());
            }
        }
    }
}
