package com.ieum.ieum.place;

import com.ieum.ieum.admin.StoreEditField;
import com.ieum.ieum.admin.StoreEditLog;
import com.ieum.ieum.admin.StoreEditLogRepository;
import com.ieum.ieum.festival.KakaoLocalClient;
import com.ieum.ieum.festival.KakaoLocalClient.Place;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.region.GeoService;
import com.ieum.ieum.region.VWorldClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 '장소 정보' 화면 (DATA-05)
 *
 * 탭: REVIEW(카카오맵 확인 필요) / NO_COORD(좌표 없음) / NO_CATEGORY(분류 없음) / LINKED(카카오맵 연결됨) / NO_PLACE(카카오맵에 없음)
 * - 카카오맵 후보는 그때그때 카카오에서 받아 화면에 보여 주기만 한다 (저장 안 함). 고른 장소의 ID만 저장.
 * - 주소를 고치면 브이월드로 좌표·지역을 다시 찾는다.
 * - 분류는 정해 둔 목록에서 고른다.
 * 바꾼 내용은 store_edit_logs 에 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPlaceService {

    static final int PAGE_SIZE = 20;
    public static final List<String> CENTURY_CATEGORIES = List.of("음식점", "카페·간식", "도소매", "생활서비스", "공방·공예", "기타");
    public static final List<String> CREATOR_CATEGORIES = List.of("로컬푸드", "지역기반제조", "지역가치", "거점브랜드",
            "지역특화관광", "디지털문화체험", "자연친화활동");

    private static final Map<String, String> TABS = new LinkedHashMap<>();

    static {
        TABS.put("REVIEW", "s.enrich_status = 'REVIEW'");
        TABS.put("NO_COORD", "s.latitude IS NULL");
        TABS.put("NO_CATEGORY", "s.category IS NULL");
        TABS.put("LINKED", "s.enrich_status = 'LINKED'");
        TABS.put("NO_PLACE", "s.enrich_status = 'NO_PLACE'");
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final KakaoLocalClient kakao;
    private final GeoService geo;
    private final StoreEditLogRepository editLogs;

    public record Row(long id, String type, String name, String category, String roadAddress, String addressRaw,
                      boolean hasCoord, String geocodeStatus, String enrichStatus, String kakaoPlaceId,
                      String kakaoMapUrl, String region, boolean visible) {
    }

    public record PlacePage(List<Row> items, long total, int page, boolean hasNext, Map<String, Long> counts,
                            List<String> centuryCategories, List<String> creatorCategories) {
    }

    public PlacePage list(String tab, String type, String q, int page) {
        String where = TABS.get(tab);
        if (where == null) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("탭을 확인해 주세요."));
        String t = "CENTURY".equals(type) || "CREATOR".equals(type) ? type : "";
        String query = q == null ? "" : q.strip();
        if (query.length() > 50) query = query.substring(0, 50);
        int p = Math.max(0, page);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("type", t).addValue("q", query)
                .addValue("offset", p * PAGE_SIZE).addValue("size", PAGE_SIZE + 1);
        String filter = " AND (:type = '' OR s.type = :type) AND (:q = '' OR s.name LIKE CONCAT('%', :q, '%') OR s.road_address LIKE CONCAT('%', :q, '%'))";
        List<Row> rows = jdbc.query("""
                SELECT s.id, s.type, s.name, s.category, s.road_address, s.address_raw, s.latitude IS NOT NULL AS has_coord,
                       s.geocode_status, s.enrich_status, s.kakao_place_id, s.is_visible,
                       CONCAT(r.sido_name, ' ', r.sigungu_name) AS region
                FROM stores s LEFT JOIN regions r ON r.code = s.sigungu_code
                """ + " WHERE " + where + filter + " ORDER BY s.is_visible DESC, s.id LIMIT :offset, :size", params,
                (rs, i) -> row(rs));
        boolean hasNext = rows.size() > PAGE_SIZE;
        if (hasNext) rows = rows.subList(0, PAGE_SIZE);
        Map<String, Long> counts = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT ");
        TABS.forEach((k, w) -> sql.append("SUM(").append(w).append(") AS ").append(k).append(", "));
        sql.setLength(sql.length() - 2);
        sql.append(" FROM stores s WHERE 1 = 1").append(filter);
        Map<String, Object> c = jdbc.queryForMap(sql.toString(), params);
        TABS.keySet().forEach(k -> counts.put(k, c.get(k) == null ? 0 : ((Number) c.get(k)).longValue()));
        return new PlacePage(rows, counts.get(tab), p, hasNext, counts, CENTURY_CATEGORIES, CREATOR_CATEGORIES);
    }

    public Row get(long id) {
        List<Row> rows = jdbc.query("""
                SELECT s.id, s.type, s.name, s.category, s.road_address, s.address_raw, s.latitude IS NOT NULL AS has_coord,
                       s.geocode_status, s.enrich_status, s.kakao_place_id, s.is_visible,
                       CONCAT(r.sido_name, ' ', r.sigungu_name) AS region
                FROM stores s LEFT JOIN regions r ON r.code = s.sigungu_code WHERE s.id = :id
                """, new MapSqlParameterSource("id", id), (rs, i) -> row(rs));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        return rows.getFirst();
    }

    private static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String pid = rs.getString("kakao_place_id");
        return new Row(rs.getLong("id"), rs.getString("type"), rs.getString("name"), rs.getString("category"),
                rs.getString("road_address"), rs.getString("address_raw"), rs.getBoolean("has_coord"),
                rs.getString("geocode_status"), rs.getString("enrich_status"), pid,
                pid == null ? null : "https://place.map.kakao.com/" + pid, rs.getString("region"), rs.getBoolean("is_visible"));
    }

    // ---------------------------------------------------------------- 카카오맵 후보 (저장 안 함)

    /** 후보 하나. distanceM 은 우리 좌표(브이월드)에서의 거리, sameName 은 이름이 같아 보이는지 */
    public record Candidate(String id, String name, String category, String address, Integer distanceM, boolean sameName) {
    }

    public record Candidates(String query, boolean available, List<Candidate> places) {
    }

    public Candidates candidates(long id, String q) {
        Map<String, Object> s = store(id);
        String name = (String) s.get("name");
        BigDecimal lat = (BigDecimal) s.get("latitude"), lng = (BigDecimal) s.get("longitude");
        List<String> names = PlaceLinkService.aliases(name);
        String query = q == null || q.isBlank() ? names.getFirst() : q.strip();
        if (query.length() > 40) query = query.substring(0, 40);
        if ((q == null || q.isBlank()) && lat == null) {
            String area = area((String) s.get("road_address"));
            if (area != null) query = query + " " + area;   // 좌표가 없으면 '가게이름 전주시'처럼 지역을 붙여 찾는다
        }
        if (!kakao.hasKey()) return new Candidates(query, false, List.of());
        List<Place> places;
        try {
            places = lat == null ? kakao.keyword(query, 15)
                    : kakao.keyword(query, 15, lat.doubleValue(), lng.doubleValue(), PlaceLinkService.SEARCH_RADIUS_M * 5);
        } catch (KakaoLocalClient.KakaoException e) {
            log.warn("관리자 카카오맵 후보 찾기 실패: {}", e.getMessage());
            return new Candidates(query, false, List.of());
        }
        List<String> targets = names.stream().map(PlaceLinkService::norm).toList();
        List<Candidate> out = places.stream().map(p -> new Candidate(p.id(), p.name(), p.category(), p.address(),
                lat == null ? null : (int) Math.round(PlaceLinkService.distance(lat.doubleValue(), lng.doubleValue(), p.lat(), p.lng())),
                PlaceLinkService.matchesAny(targets, p.name()))).toList();
        return new Candidates(query, true, out);
    }

    /** '전북 전주시 덕진구 …' → '전주시' */
    static String area(String address) {
        if (address == null) return null;
        String[] t = address.strip().split("\\s+");
        return t.length >= 2 ? t[1] : null;
    }

    // ---------------------------------------------------------------- 바꾸기

    @Transactional
    public Row link(long adminId, long id, String placeId) {
        String pid = placeId == null ? "" : placeId.strip();
        if (!pid.matches("\\d{1,20}")) throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("카카오맵 장소 ID는 숫자예요."));
        Map<String, Object> s = store(id);
        jdbc.update("UPDATE stores SET kakao_place_id = :pid, enrich_status = 'LINKED' WHERE id = :id",
                new MapSqlParameterSource().addValue("pid", pid).addValue("id", id));
        log(adminId, id, StoreEditField.KAKAO_PLACE, (String) s.get("kakao_place_id"), pid);
        return get(id);
    }

    /** 연결 풀기 (다시 확인 필요로) 또는 '카카오맵에 없음' */
    @Transactional
    public Row unlink(long adminId, long id, boolean noPlace) {
        Map<String, Object> s = store(id);
        jdbc.update("UPDATE stores SET kakao_place_id = NULL, enrich_status = :st WHERE id = :id",
                new MapSqlParameterSource().addValue("st", noPlace ? "NO_PLACE" : "REVIEW").addValue("id", id));
        log(adminId, id, StoreEditField.KAKAO_PLACE, (String) s.get("kakao_place_id"), noPlace ? "카카오맵에 없음" : null);
        return get(id);
    }

    @Transactional
    public Row category(long adminId, long id, String category) {
        Map<String, Object> s = store(id);
        String value = category == null || category.isBlank() ? null : category.strip();
        List<String> allowed = "CREATOR".equals(s.get("type")) ? CREATOR_CATEGORIES : CENTURY_CATEGORIES;
        if (value != null && !allowed.contains(value)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("분류는 목록에서 골라 주세요."));
        }
        jdbc.update("UPDATE stores SET category = :c WHERE id = :id", new MapSqlParameterSource().addValue("c", value).addValue("id", id));
        log(adminId, id, StoreEditField.CATEGORY, (String) s.get("category"), value);
        return get(id);
    }

    /** 주소 고치기 (오타 등): 브이월드로 좌표·지역을 다시 찾는다. 카카오맵 연결은 그대로 둔다 */
    @Transactional
    public Row address(long adminId, long id, String roadAddress) {
        String address = roadAddress == null ? "" : roadAddress.strip().replaceAll("\\s+", " ");
        if (address.length() < 5 || address.length() > 200) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("주소는 5~200자로 넣어 주세요."));
        }
        Map<String, Object> s = store(id);
        if (!geo.available()) throw new BusinessException(ErrorCode.MAP_SERVICE_UNAVAILABLE);
        GeoService.Located p;
        try {
            p = geo.locate(address).orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));
        } catch (VWorldClient.VWorldException e) {
            log.warn("관리자 주소 확인 실패: {}", e.getMessage());
            throw new BusinessException(ErrorCode.MAP_SERVICE_UNAVAILABLE);
        }
        String jibun = null;
        try {
            jibun = geo.parcelAddress(p.lat(), p.lng()).orElse(null);
        } catch (VWorldClient.VWorldException e) {
            // 지번은 없어도 된다
        }
        jdbc.update("""
                UPDATE stores SET road_address = :addr, jibun_address = :jibun, latitude = :lat, longitude = :lng,
                                  sigungu_code = :sigungu, sido_code = :sido, b_code = NULL, geocode_status = 'OK'
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("addr", address).addValue("lat", p.lat()).addValue("lng", p.lng())
                .addValue("sigungu", p.sigunguCode()).addValue("sido", p.sidoCode()).addValue("jibun", jibun).addValue("id", id));
        log(adminId, id, StoreEditField.ROAD_ADDRESS, (String) s.get("road_address"), address);
        return get(id);
    }

    private Map<String, Object> store(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, type, name, category, road_address, latitude, longitude, kakao_place_id FROM stores WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        return rows.getFirst();
    }

    private void log(long adminId, long storeId, StoreEditField field, String before, String after) {
        if (java.util.Objects.equals(before, after)) return;
        editLogs.save(StoreEditLog.of(storeId, adminId, field, before, after));
    }
}
