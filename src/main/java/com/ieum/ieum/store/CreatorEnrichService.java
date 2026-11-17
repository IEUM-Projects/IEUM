package com.ieum.ieum.store;

import com.ieum.ieum.festival.KakaoLocalClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로컬크리에이터 좌표·지역 채우기 (LOC-01)
 *
 * 20_data_creators.sql 로 넣은 로컬크리에이터(stores.type=CREATOR)는 원본에 좌표가 없어서
 * 서버를 켤 때 카카오 주소 검색으로 좌표를 찾고, 좌표로 지역 코드(regions 표와 같은 코드)를 정한다.
 * geocode_status: PENDING(아직) → OK(찾음) / FAILED(못 찾음, 지도에 안 나옴).
 * 이미 채운 곳은 다시 부르지 않아서 두 번째부터는 금방 끝난다.
 * 원본 주소 뒤에 '(집현동) A동 301호' 같은 상세 주소가 붙어 있으면 못 찾을 수 있어서, 도로명+건물번호까지만 잘라 다시 찾는다.
 * 지역 표(regions)에는 백년가게가 있는 시·군·구만 들어 있어서, 카카오가 준 코드가 그 표에 없으면 지역 코드는 비워 둔다
 * (stores.sigungu_code 는 regions 를 참조함). 좌표는 그대로 저장해서 지도·코스에는 나온다.
 * 한 곳을 저장하다 오류가 나도 그곳만 FAILED 로 두고 나머지는 계속한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreatorEnrichService {

    private static final Pattern ROAD = Pattern.compile("^(.+?(?:로|길)\\s?\\d+(?:-\\d+)?)(?:\\D.*)?$");
    private static final Pattern JIBUN = Pattern.compile("^(.+?(?:동|리|가)\\s?(?:산\\s?)?\\d+(?:-\\d+)?)(?:\\D.*)?$");

    private final NamedParameterJdbcTemplate jdbc;
    private final KakaoLocalClient kakao;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public record Result(int found, int failed) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Thread.ofVirtual().name("creator-enrich").start(() -> {
            try {
                enrich();
            } catch (RuntimeException e) {
                log.error("로컬크리에이터 좌표 채우기 중 오류", e);
            }
        });
    }

    public Optional<Result> enrich() {
        if (!running.compareAndSet(false, true)) return Optional.empty();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, road_address FROM stores WHERE type = 'CREATOR' AND geocode_status = 'PENDING' ORDER BY id",
                    new MapSqlParameterSource());
            if (rows.isEmpty()) return Optional.of(new Result(0, 0));
            if (!kakao.hasKey()) {
                log.warn("kakao.rest-api-key가 없어서 로컬크리에이터 {}곳의 좌표를 채우지 못했어요.", rows.size());
                return Optional.of(new Result(0, 0));
            }
            java.util.Set<String> regionCodes = new java.util.HashSet<>(
                    jdbc.queryForList("SELECT code FROM regions", new MapSqlParameterSource(), String.class));
            int found = 0, failed = 0, noRegion = 0;
            for (Map<String, Object> r : rows) {
                long id = ((Number) r.get("id")).longValue();
                String address = (String) r.get("road_address");
                Optional<KakaoLocalClient.Point> point = Optional.empty();
                try {
                    for (String candidate : candidates(address)) {
                        point = kakao.geocode(candidate);
                        if (point.isPresent()) break;
                    }
                } catch (KakaoLocalClient.KakaoException e) {
                    log.warn("로컬크리에이터 좌표 채우기를 멈춤 (다음에 서버를 켤 때 이어서): {}", e.getMessage());
                    break;
                }
                if (point.isEmpty()) {
                    jdbc.update("UPDATE stores SET geocode_status = 'FAILED' WHERE id = :id", new MapSqlParameterSource("id", id));
                    failed++;
                    continue;
                }
                String sigungu = null;
                try {
                    sigungu = kakao.sigunguCode(point.get().lat(), point.get().lng()).orElse(null);
                } catch (KakaoLocalClient.KakaoException e) {
                    // 지역 코드는 없어도 지도에는 나온다
                }
                if (sigungu != null && !regionCodes.contains(sigungu)) {
                    sigungu = null;     // 지역 표에 없는 시·군·구
                    noRegion++;
                }
                try {
                jdbc.update("""
                        UPDATE stores SET latitude = :lat, longitude = :lng, sigungu_code = :sigungu, sido_code = :sido, geocode_status = 'OK'
                        WHERE id = :id
                        """, new MapSqlParameterSource().addValue("id", id).addValue("lat", point.get().lat())
                        .addValue("lng", point.get().lng()).addValue("sigungu", sigungu)
                        .addValue("sido", sigungu == null ? null : sigungu.substring(0, 2)));
                } catch (org.springframework.dao.DataAccessException e) {
                    log.warn("로컬크리에이터 {} 저장 실패: {}", id, e.getMostSpecificCause().getMessage());
                    jdbc.update("UPDATE stores SET geocode_status = 'FAILED' WHERE id = :id", new MapSqlParameterSource("id", id));
                    failed++;
                    continue;
                }
                found++;
            }
            log.info("로컬크리에이터 좌표 채우기: 찾음 {}곳 (그중 지역 표에 없는 시·군·구 {}곳), 못 찾음 {}곳", found, noRegion, failed);
            return Optional.of(new Result(found, failed));
        } finally {
            running.set(false);
        }
    }

    /** 원본 주소 → 그대로, 괄호를 뺀 것, 건물번호까지 자른 것 (겹치면 한 번만) */
    static List<String> candidates(String address) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (address == null || address.isBlank()) return List.of();
        String a = address.strip();
        out.add(a);
        String noParen = a.replaceAll("\\s*\\([^)]*\\)", "").replaceAll("\\s+", " ").strip();
        out.add(noParen);
        for (Pattern p : List.of(ROAD, JIBUN)) {
            Matcher m = p.matcher(noParen);
            if (m.matches()) out.add(m.group(1).strip());
        }
        return new ArrayList<>(out);
    }
}
