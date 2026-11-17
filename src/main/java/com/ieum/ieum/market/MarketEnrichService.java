package com.ieum.ieum.market;

import com.ieum.ieum.region.GeoService;
import com.ieum.ieum.region.VWorldClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 시장 좌표·지역 채우기 (MKT-01, DATA-05에서 카카오 → 브이월드)
 *
 * 08_data_markets.sql로 넣은 시장 중
 * - 원본에 좌표가 없는 시장 → 브이월드 주소 검색으로 좌표를 찾고
 * - 모든 시장 → 지역 코드(regions 표와 같은 코드)를 정한다: 주소 글자로 먼저, 모르면 브이월드 좌표 → 주소
 * 서버를 켤 때 한 번 실행한다. 이미 채운 시장은 다시 부르지 않으므로 두 번째부터는 금방 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketEnrichService {

    private final MarketRepository repository;
    private final GeoService geo;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public record Result(int geocoded, int geocodeFailed, int regioned) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread.ofVirtual().name("market-enrich").start(() -> {
            try {
                enrich();
            } catch (RuntimeException e) {
                log.error("시장 좌표·지역 채우기 중 오류", e);
            }
        });
    }

    public Optional<Result> enrich() {
        if (!running.compareAndSet(false, true)) return Optional.empty();
        try {
            int geocoded = 0, failed = 0, regioned = 0;
            try {
                if (geo.available()) {
                    for (Market m : repository.findNeedingGeocode()) {
                        Optional<GeoService.Located> p = Optional.empty();
                        try {
                            if (m.getRoadAddress() != null) p = geo.locate(m.getRoadAddress());
                            if (p.isEmpty() && m.getJibunAddress() != null) p = geo.locate(m.getJibunAddress());
                        } catch (VWorldClient.VWorldException e) {
                            if (!e.isRetryable()) throw e;
                            log.warn("시장 {} 좌표 찾기를 건너뜀 (다음에 다시): {}", m.getId(), e.getMessage());
                            continue;   // 잠깐의 오류: 이 시장만 다음에 다시
                        }
                        if (p.isPresent()) {
                            m.geocoded(p.get().lat(), p.get().lng());
                            if (p.get().sigunguCode() != null) m.region(p.get().sigunguCode());
                            geocoded++;
                        } else {
                            m.geocodeFailed();
                            failed++;
                        }
                        repository.save(m);
                    }
                } else {
                    log.warn("vworld.api-key가 없어서 좌표 없는 시장의 좌표를 채우지 못했어요.");
                }
                for (Market m : repository.findNeedingRegion()) {
                    Optional<String> code;
                    try {
                        code = geo.regionOf(m.address(), m.getLatitude().doubleValue(), m.getLongitude().doubleValue());
                    } catch (VWorldClient.VWorldException e) {
                        if (!e.isRetryable()) throw e;
                        continue;
                    }
                    if (code.isPresent()) {
                        m.region(code.get());
                        repository.save(m);
                        regioned++;
                    }
                }
            } catch (VWorldClient.VWorldException e) {
                log.warn("브이월드 주소 검색을 쓸 수 없어 시장 좌표·지역 채우기를 멈춰요 (다음에 서버를 켤 때 이어서): {}", e.getMessage());
            }
            Result result = new Result(geocoded, failed, regioned);
            // 새로 채울 곳이 없어도 남긴다 (조용하면 돌았는지 알 수 없어서)
            log.info("시장 좌표·지역 채우기 끝: 좌표 찾음 {}곳, 못 찾음 {}곳, 지역 정함 {}곳", geocoded, failed, regioned);
            return Optional.of(result);
        } finally {
            running.set(false);
        }
    }
}
