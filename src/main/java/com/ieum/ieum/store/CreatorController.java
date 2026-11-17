package com.ieum.ieum.store;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 로컬크리에이터 지역별 모아 보기 (LOC-01). 누구나 조회.
 *
 * GET /api/creators/regions                                   시도·시군구별 로컬크리에이터 수
 * GET /api/creators?sido=강원&sigungu=강릉시&field=로컬푸드&page=0   그 지역의 로컬크리에이터 전부 (30곳씩, 최근 선정 순)
 * 지역은 주소에서 뽑은 이름(stores.area_sido, area_sigungu)으로 묶는다. 반경과 상관없이 그 지역 전체.
 * 좌표를 아직 못 찾은 곳도 목록에는 나온다 (mapped=false).
 */
@RestController
@RequestMapping("/api/creators")
@RequiredArgsConstructor
public class CreatorController {

    public static final List<String> SIDO_ORDER = List.of("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종",
            "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주");
    public static final List<String> FIELDS = List.of("로컬푸드", "지역기반제조", "지역가치", "거점브랜드", "지역특화관광", "자연친화활동", "디지털문화체험");
    static final int PAGE_SIZE = 30;

    private final NamedParameterJdbcTemplate jdbc;

    public record Sigungu(String name, long count) {
    }

    public record SidoGroup(String sido, long count, List<Sigungu> sigungus) {
    }

    public record CreatorItem(Long id, String name, List<String> fields, String item, int latestYear, List<Integer> years,
                              String roadAddress, BigDecimal latitude, BigDecimal longitude, boolean mapped) {
    }

    public record CreatorPage(String sido, String sigungu, String field, long total, int page, int size, boolean hasNext,
                              List<CreatorItem> items, String source) {
    }

    @GetMapping("/regions")
    public List<SidoGroup> regions() {
        Map<String, List<Sigungu>> bySido = new LinkedHashMap<>();
        Map<String, Long> sidoCount = new LinkedHashMap<>();
        jdbc.query("""
                SELECT area_sido, area_sigungu, COUNT(*) AS n FROM stores
                WHERE type = 'CREATOR' AND is_visible = 1 AND area_sido IS NOT NULL
                GROUP BY area_sido, area_sigungu ORDER BY area_sido, area_sigungu
                """, new MapSqlParameterSource(), rs -> {
            String sido = rs.getString("area_sido");
            String sigungu = rs.getString("area_sigungu");
            long n = rs.getLong("n");
            sidoCount.merge(sido, n, Long::sum);
            List<Sigungu> list = bySido.computeIfAbsent(sido, k -> new ArrayList<>());
            if (sigungu != null) list.add(new Sigungu(sigungu, n));
        });
        return SIDO_ORDER.stream().filter(sidoCount::containsKey)
                .map(s -> new SidoGroup(s, sidoCount.get(s), bySido.getOrDefault(s, List.of()))).toList();
    }

    @GetMapping
    public CreatorPage list(@RequestParam(defaultValue = "") String sido, @RequestParam(defaultValue = "") String sigungu,
                            @RequestParam(defaultValue = "") String field, @RequestParam(defaultValue = "0") int page) {
        if (!sido.isEmpty() && !SIDO_ORDER.contains(sido)) bad("시도 이름을 확인해 주세요.");
        if (!sigungu.isEmpty() && (sido.isEmpty() || sigungu.length() > 20)) bad("시·군·구는 시도와 함께 넣어 주세요.");
        if (!field.isEmpty() && !FIELDS.contains(field)) bad("분야 이름을 확인해 주세요.");
        if (page < 0 || page > 1000) bad("page 값을 확인해 주세요.");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("sido", sido).addValue("sigungu", sigungu)
                .addValue("field", field).addValue("limit", PAGE_SIZE + 1).addValue("offset", page * PAGE_SIZE);
        String where = """
                s.type = 'CREATOR' AND s.is_visible = 1
                  AND (:sido = '' OR s.area_sido = :sido)
                  AND (:sigungu = '' OR s.area_sigungu = :sigungu)
                  AND (:field = '' OR EXISTS (SELECT 1 FROM creator_selections f WHERE f.store_id = s.id AND FIND_IN_SET(:field, f.fields)))
                """;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM stores s WHERE " + where, p, Long.class);
        List<CreatorItem> items = new ArrayList<>(jdbc.query("""
                SELECT s.id, s.name, s.road_address, s.latitude, s.longitude,
                       MAX(c.selected_year) AS latest,
                       GROUP_CONCAT(c.selected_year ORDER BY c.selected_year DESC) AS years,
                       GROUP_CONCAT(IFNULL(c.fields, '') ORDER BY c.selected_year DESC SEPARATOR ',') AS fields,
                       SUBSTRING_INDEX(GROUP_CONCAT(IFNULL(c.item, '') ORDER BY c.selected_year DESC SEPARATOR '\\n'), '\\n', 1) AS item
                FROM stores s JOIN creator_selections c ON c.store_id = s.id
                WHERE\s""" + where + """
                GROUP BY s.id, s.name, s.road_address, s.latitude, s.longitude
                ORDER BY latest DESC, s.name, s.id
                LIMIT :limit OFFSET :offset
                """, p, (rs, i) -> {
            List<String> raw = Arrays.stream(rs.getString("fields").split(",")).filter(f -> !f.isBlank()).toList();
            List<String> fields = FIELDS.stream().filter(raw::contains).toList();
            List<Integer> years = Arrays.stream(rs.getString("years").split(",")).map(Integer::valueOf).toList();
            BigDecimal lat = rs.getBigDecimal("latitude");
            BigDecimal lng = rs.getBigDecimal("longitude");
            String item = rs.getString("item");
            return new CreatorItem(rs.getLong("id"), rs.getString("name"), fields, item == null || item.isBlank() ? null : item,
                    rs.getInt("latest"), years, rs.getString("road_address"), lat, lng, lat != null && lng != null);
        }));
        boolean hasNext = items.size() > PAGE_SIZE;
        if (hasNext) items.removeLast();
        return new CreatorPage(sido, sigungu, field, total == null ? 0 : total, page, PAGE_SIZE, hasNext, items,
                "중소벤처기업부 로컬크리에이터 리스트 (2026-09-16 기준)");
    }

    private static void bad(String message) {
        throw new BusinessException(ErrorCode.INVALID_INPUT, List.of(message));
    }
}
