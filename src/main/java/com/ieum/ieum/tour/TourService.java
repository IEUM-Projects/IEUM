package com.ieum.ieum.tour;

import com.ieum.ieum.festival.PublicDataClient;
import com.ieum.ieum.festival.PublicDataClient.TourSpotRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주변 관광지 (TOUR-01): 한국관광공사 위치 기반 조회 + DB 저장
 *
 * 관광공사 API는 하루 호출 수가 정해져 있고 느릴 수 있어서, 동네를 약 2km 네모 칸(위도·경도 0.02도)으로 나눠
 * 칸마다 '칸 가운데에서 3.5km 안'을 한 번 받아 tour_spots 에 저장하고 7일 동안 다시 쓴다.
 * 칸 가운데에서 칸 끝까지는 1.5km가 안 되고 찾는 반경은 최대 2km라서, 칸 안 어느 점에서 찾아도 받아 둔 3.5km 안에 들어간다.
 * API가 실패하면 저장해 둔 것(오래됐어도)을 보여 주고, 하나도 없으면 available=false. 실패한 칸은 10분 동안 다시 부르지 않는다.
 * 화면이 기다리는 호출이라 관광공사 응답은 8초까지만 기다리고, 한 종류라도 실패하면 그 요청에서는 나머지 종류를 부르지 않는다
 * (느릴 때 종류 수만큼 기다리지 않게).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TourService {

    /** 관광공사 관광타입: 12 관광지, 14 문화시설, 28 레포츠, 25 여행코스 (보여 주는 순서) */
    public static final Map<Integer, String> TYPES = orderedTypes();
    public static final List<Integer> ALL_TYPES = List.copyOf(TYPES.keySet());
    public static final int MAX_RADIUS = 2000;
    static final double CELL = 0.02;
    static final int API_RADIUS = 3500;
    /** 받는 반경이 바뀌면 칸 이름도 바꿔서 예전 반경으로 받은 칸을 다시 받게 한다 */
    static final String CELL_VERSION = "r35";
    static final int MAX_PAGES = 3;
    static final Duration FRESH = Duration.ofDays(7);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PublicDataClient client;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** 실패한 칸은 잠시 다시 부르지 않는다 (느리거나 한도를 넘었을 때 화면마다 기다리지 않게) */
    private final Map<String, LocalDateTime> retryAfter = new ConcurrentHashMap<>();
    static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(10);

    public record Spot(String id, int type, String typeName, String title, String address, double latitude, double longitude,
                       String imageUrl, String imageLicense, long distanceM) {
    }

    public record Nearby(List<Spot> spots, boolean available) {
    }

    private static Map<Integer, String> orderedTypes() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(12, "관광지");
        m.put(14, "문화시설");
        m.put(28, "레포츠");
        m.put(25, "여행코스");
        return Collections.unmodifiableMap(m);
    }

    /** (lat, lng)에서 radius(m) 안의 관광 정보, 가까운 순 */
    public Nearby nearby(double lat, double lng, int radius, List<Integer> types, int limit) {
        int r = Math.clamp(radius, 100, MAX_RADIUS);
        boolean available = true;
        for (int type : types) {
            if (!ensureCell(lat, lng, type)) {
                available = false;
                break;     // 관광공사가 안 되면 나머지 종류는 저장된 것만 쓴다
            }
        }
        List<Spot> spots = query(lat, lng, r, types, limit);
        return new Nearby(spots, available || !spots.isEmpty());
    }

    static String cellKey(double lat, double lng, int type) {
        return Math.round(lat / CELL) + ":" + Math.round(lng / CELL) + ":" + type + ":" + CELL_VERSION;
    }

    /** 이 점이 들어 있는 칸을 7일 안에 받았는지 확인하고, 아니면 받는다. 받지 못하면 false (저장된 것이 있으면 그것을 씀) */
    boolean ensureCell(double lat, double lng, int type) {
        String key = cellKey(lat, lng, type);
        if (fresh(key)) return true;
        LocalDateTime wait = retryAfter.get(key);
        if (wait != null && wait.isAfter(LocalDateTime.now(SEOUL))) return false;
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            if (fresh(key)) return true;
            if (!client.hasKey()) return false;
            wait = retryAfter.get(key);
            if (wait != null && wait.isAfter(LocalDateTime.now(SEOUL))) return false;
            double centerLat = Math.round(lat / CELL) * CELL;
            double centerLng = Math.round(lng / CELL) * CELL;
            List<TourSpotRecord> got;
            try {
                got = client.nearbySpots(centerLat, centerLng, API_RADIUS, type, MAX_PAGES);
            } catch (PublicDataClient.PublicDataException e) {
                log.warn("관광공사 주변 관광정보 실패 ({}), {}분 뒤 다시 시도: {}", key, RETRY_AFTER_FAILURE.toMinutes(), e.getMessage());
                retryAfter.put(key, LocalDateTime.now(SEOUL).plus(RETRY_AFTER_FAILURE));
                return false;
            }
            LocalDateTime now = LocalDateTime.now(SEOUL);
            transaction.executeWithoutResult(s -> {
                for (TourSpotRecord t : got) {
                    jdbc.update("""
                            INSERT INTO tour_spots (content_id, content_type, title, address, latitude, longitude, image_url, image_license, fetched_at)
                            VALUES (:id, :type, :title, :address, :lat, :lng, :image, :license, :now)
                            ON DUPLICATE KEY UPDATE content_type = VALUES(content_type), title = VALUES(title), address = VALUES(address),
                              latitude = VALUES(latitude), longitude = VALUES(longitude), image_url = VALUES(image_url),
                              image_license = VALUES(image_license), fetched_at = VALUES(fetched_at)
                            """, new MapSqlParameterSource().addValue("id", t.contentId()).addValue("type", t.contentType())
                            .addValue("title", t.title() == null ? "(이름 없음)" : t.title()).addValue("address", t.address())
                            .addValue("lat", t.latitude()).addValue("lng", t.longitude()).addValue("image", t.imageUrl())
                            .addValue("license", t.imageLicense()).addValue("now", Timestamp.valueOf(now)));
                }
                jdbc.update("""
                        INSERT INTO tour_area_fetches (cell_key, fetched_at, spot_count) VALUES (:key, :now, :n)
                        ON DUPLICATE KEY UPDATE fetched_at = VALUES(fetched_at), spot_count = VALUES(spot_count)
                        """, new MapSqlParameterSource().addValue("key", key).addValue("now", Timestamp.valueOf(now)).addValue("n", got.size()));
            });
            retryAfter.remove(key);
            log.info("관광공사 주변 관광정보 {}: {}곳 저장", key, got.size());
            return true;
        }
    }

    private boolean fresh(String key) {
        List<Timestamp> t = jdbc.queryForList("SELECT fetched_at FROM tour_area_fetches WHERE cell_key = :key",
                new MapSqlParameterSource("key", key), Timestamp.class);
        return !t.isEmpty() && t.getFirst().toLocalDateTime().isAfter(LocalDateTime.now(SEOUL).minus(FRESH));
    }

    private List<Spot> query(double lat, double lng, int radius, List<Integer> types, int limit) {
        if (types.isEmpty()) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("lat", lat).addValue("lng", lng)
                .addValue("r", radius).addValue("types", types).addValue("limit", limit);
        List<Spot> out = new ArrayList<>(jdbc.query("""
                SELECT content_id, content_type, title, address, latitude, longitude, image_url, image_license,
                       ROUND(ST_Distance_Sphere(POINT(longitude, latitude), POINT(:lng, :lat))) AS d
                FROM tour_spots
                WHERE content_type IN (:types)
                  AND latitude BETWEEN :lat - :r / 111320.0 AND :lat + :r / 111320.0
                  AND longitude BETWEEN :lng - :r / (111320.0 * COS(RADIANS(:lat))) AND :lng + :r / (111320.0 * COS(RADIANS(:lat)))
                  AND ST_Distance_Sphere(POINT(longitude, latitude), POINT(:lng, :lat)) <= :r
                ORDER BY d, content_id
                LIMIT :limit
                """, p, (rs, i) -> new Spot(rs.getString("content_id"), rs.getInt("content_type"),
                TYPES.getOrDefault(rs.getInt("content_type"), "관광"), rs.getString("title"), rs.getString("address"),
                rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getString("image_url"), rs.getString("image_license"),
                rs.getLong("d"))));
        return out;
    }
}
