package com.ieum.ieum.market;

import com.ieum.ieum.festival.KakaoLocalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 시장 좌표·지역 채우기 (MKT-01)
 *
 * 08_data_markets.sql로 넣은 시장 중
 * - 원본에 좌표가 없는 시장 → 카카오 주소 검색으로 좌표를 찾고
 * - 모든 시장 → 좌표로 지역 코드(regions 표와 같은 코드)를 정한다.
 * 서버를 켤 때 한 번 실행한다. 이미 채운 시장은 다시 부르지 않으므로 두 번째부터는 금방 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketEnrichService {

    private final MarketRepository repository;
    private final KakaoLocalClient kakao;
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
            if (!kakao.hasKey()) {
                log.warn("kakao.rest-api-key가 없어서 시장 좌표·지역을 채우지 못했어요.");
                return Optional.of(new Result(0, 0, 0));
            }
            int geocoded = 0, failed = 0, regioned = 0;
            try {
                for (Market m : repository.findNeedingGeocode()) {
                    Optional<KakaoLocalClient.Point> point = Optional.empty();
                    if (m.getRoadAddress() != null) point = kakao.geocode(m.getRoadAddress());
                    if (point.isEmpty() && m.getJibunAddress() != null) point = kakao.geocode(m.getJibunAddress());
                    if (point.isPresent()) {
                        m.geocoded(point.get().lat(), point.get().lng());
                        geocoded++;
                    } else {
                        m.geocodeFailed();
                        failed++;
                    }
                    repository.save(m);
                }
                for (Market m : repository.findNeedingRegion()) {
                    Optional<String> code = kakao.sigunguCode(m.getLatitude().doubleValue(), m.getLongitude().doubleValue());
                    if (code.isPresent()) {
                        m.region(code.get());
                        repository.save(m);
                        regioned++;
                    }
                }
            } catch (KakaoLocalClient.KakaoException e) {
                log.warn("카카오 로컬 API를 쓸 수 없어 시장 좌표·지역 채우기를 멈춰요 (다음에 서버를 켤 때 이어서): {}", e.getMessage());
            }
            Result result = new Result(geocoded, failed, regioned);
            if (geocoded + failed + regioned > 0) log.info("시장 좌표·지역 채우기 끝: {}", result);
            return Optional.of(result);
        } finally {
            running.set(false);
        }
    }
}
