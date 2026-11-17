package com.ieum.ieum.place;

import com.ieum.ieum.region.GeoService;
import com.ieum.ieum.region.VWorldClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 가게 좌표·지역 채우기 (DATA-05, LOC-01의 CreatorEnrichService를 백년가게까지 넓힘)
 *
 * stores.geocode_status: PENDING(아직) → OK(찾음) / FAILED(못 찾음, 지도에 안 나옴, 관리자 화면 '좌표 없음'에서 주소를 고침)
 * 서버를 켤 때 PENDING 인 가게를 브이월드 주소 검색으로 채우고, 이어서 카카오맵 장소 연결(PlaceLinkService)을 한다.
 * 이미 채운 곳은 다시 부르지 않아서 두 번째부터는 금방 끝난다.
 * 브이월드 키·한도 문제가 생기면 멈추고, 다음에 서버를 켜거나 관리자가 '다시 찾기'를 누르면 이어서 한다.
 * 잠깐의 서버 오류(502 등)는 VWorldClient가 다시 해 보고, 그래도 안 된 가게는 PENDING 으로 두고 다음 가게로 넘어간다.
 * 그런 가게가 연달아 MAX_SKIPS_IN_ROW 곳이면 브이월드가 멈춘 것으로 보고 그만둔다.
 * 한 곳을 저장하다 오류가 나도 그곳만 FAILED 로 두고 나머지는 계속한다.
 */
@Slf4j
@Service
public class StoreGeocodeService {

    static final int MAX_SKIPS_IN_ROW = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final GeoService geo;
    private final PlaceLinkService link;
    private volatile PlaceJob job;

    public StoreGeocodeService(NamedParameterJdbcTemplate jdbc, GeoService geo, @Lazy PlaceLinkService link) {
        this.jdbc = jdbc;
        this.geo = geo;
        this.link = link;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        start(false, true);
    }

    public Optional<PlaceJob.View> current() {
        PlaceJob j = job;
        return j == null ? Optional.empty() : Optional.of(j.view());
    }

    public boolean isRunning() {
        PlaceJob j = job;
        return j != null && "RUNNING".equals(j.state);
    }

    /**
     * 좌표 찾기 시작. retryFailed = 못 찾았던 곳(FAILED)도 다시. thenLink = 끝나면 카카오맵 자동 연결도.
     * 이미 하고 있으면 빈 값.
     */
    public synchronized Optional<PlaceJob.View> start(boolean retryFailed, boolean thenLink) {
        if (isRunning() || link.isRunning()) return Optional.empty();
        PlaceJob j = new PlaceJob("GEOCODE");
        job = j;
        Thread.ofVirtual().name("store-geocode").start(() -> {
            try {
                run(j, retryFailed);
            } catch (RuntimeException e) {
                log.error("가게 좌표 채우기 중 오류", e);
                j.finish("FAILED", "오류로 멈췄어요: " + e.getClass().getSimpleName());
            }
            if (thenLink) link.autoLink();
        });
        return Optional.of(j.view());
    }

    void run(PlaceJob j, boolean retryFailed) {
        if (retryFailed) {
            jdbc.update("UPDATE stores SET geocode_status = 'PENDING' WHERE geocode_status = 'FAILED'", new MapSqlParameterSource());
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, road_address, address_raw FROM stores WHERE geocode_status = 'PENDING' ORDER BY id",
                new MapSqlParameterSource());
        j.total = rows.size();
        if (rows.isEmpty()) {
            log.info("가게 좌표 채우기: 새로 찾을 가게가 없어요.");
            j.finish("DONE", "새로 찾을 가게가 없어요.");
            return;
        }
        if (!geo.available()) {
            log.warn("vworld.api-key가 없어서 가게 {}곳의 좌표를 채우지 못했어요.", rows.size());
            j.finish("FAILED", "브이월드 키(vworld.api-key)가 없어서 좌표를 찾지 못했어요.");
            return;
        }
        log.info("가게 좌표 채우기 시작: {}곳 (몇 분 걸려요. 진행 상황은 200곳마다 이 로그와 관리자 › 장소 정보에)", rows.size());
        int noRegion = 0, skipped = 0, skipsInRow = 0;
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String road = (String) r.get("road_address");
            String raw = (String) r.get("address_raw");
            Optional<GeoService.Located> found;
            try {
                found = geo.locate(road);
                if (found.isEmpty() && raw != null && !raw.equals(road)) found = geo.locate(raw);
            } catch (VWorldClient.VWorldException e) {
                if (e.isRetryable() && ++skipsInRow < MAX_SKIPS_IN_ROW) {
                    log.warn("가게 {} 좌표 찾기를 건너뜀 (다음에 다시): {}", id, e.getMessage());
                    skipped++;
                    j.done.incrementAndGet();
                    continue;
                }
                log.warn("가게 좌표 채우기를 멈춤 (다음에 이어서): {}", e.getMessage());
                j.finish("FAILED", "브이월드 주소 검색을 쓸 수 없어 멈췄어요 (" + e.getMessage() + "). 남은 곳은 다음에 이어서 찾아요.");
                return;
            }
            skipsInRow = 0;
            if (j.done.incrementAndGet() % 200 == 0) log.info("가게 좌표 채우기: {}/{}곳 했어요", j.done.get(), j.total);
            if (found.isEmpty()) {
                failed(id);
                j.failed.incrementAndGet();
                continue;
            }
            GeoService.Located p = found.get();
            if (p.sigunguCode() == null) noRegion++;
            String jibun = null;
            try {
                jibun = geo.parcelAddress(p.lat(), p.lng()).orElse(null);   // 인허가 확인(DATA-02)에서 지번으로도 맞춰 보려고
            } catch (VWorldClient.VWorldException e) {
                // 지번은 없어도 된다
            }
            try {
                jdbc.update("""
                        UPDATE stores SET latitude = :lat, longitude = :lng, sigungu_code = :sigungu, sido_code = :sido,
                                          jibun_address = :jibun, geocode_status = 'OK'
                        WHERE id = :id AND geocode_status = 'PENDING'
                        """, new MapSqlParameterSource().addValue("id", id).addValue("lat", p.lat()).addValue("lng", p.lng())
                        .addValue("jibun", jibun)
                        .addValue("sigungu", p.sigunguCode()).addValue("sido", p.sidoCode()));
                j.found.incrementAndGet();
            } catch (org.springframework.dao.DataAccessException e) {
                log.warn("가게 {} 좌표 저장 실패: {}", id, e.getMostSpecificCause().getMessage());
                failed(id);
                j.failed.incrementAndGet();
            }
        }
        String msg = "좌표를 찾은 곳 " + j.found.get() + "곳, 못 찾은 곳 " + j.failed.get() + "곳"
                + (noRegion > 0 ? " (지역 표에 없는 시·군·구 " + noRegion + "곳)" : "")
                + (skipped > 0 ? ", 브이월드 오류로 건너뛴 곳 " + skipped + "곳 (다음에 다시)" : "");
        log.info("가게 좌표 채우기: {}", msg);
        j.finish("DONE", msg + ".");
    }

    private void failed(long id) {
        jdbc.update("UPDATE stores SET geocode_status = 'FAILED' WHERE id = :id AND geocode_status = 'PENDING'",
                new MapSqlParameterSource("id", id));
    }
}
