package com.ieum.ieum.search;

import com.ieum.ieum.region.RegionRepository;
import com.ieum.ieum.search.SearchDtos.FestivalHit;
import com.ieum.ieum.search.SearchDtos.Group;
import com.ieum.ieum.search.SearchDtos.MarketHit;
import com.ieum.ieum.search.SearchDtos.PostHit;
import com.ieum.ieum.search.SearchDtos.PageResult;
import com.ieum.ieum.search.SearchDtos.RegionHit;
import com.ieum.ieum.search.SearchDtos.SearchResult;
import com.ieum.ieum.search.SearchDtos.StoreHit;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 통합 검색 (SRC-01): 백년가게, 전통시장, 축제, 지역, 동네 이야기 글(SRCH-02)
 *
 * 데이터가 수천 건이라 LIKE 검색으로 충분하다 (전문 검색 색인은 데이터가 많아지면 검토).
 * 쿼리 모양(낱말 수만큼 조건)은 코드가 만들지만, 검색어 값은 모두 바인딩 변수로만 넣는다 (SQL 주입 방지).
 *
 * 순서: 이름이 검색어와 같음 → 이름이 검색어로 시작 → 이름에 검색어 → 주소 등 다른 칸에서 찾음,
 *       같은 순위 안에서는 기준 위치가 있으면 가까운 순, 없으면 이름 순.
 */
@Service
@RequiredArgsConstructor
public class SearchService {

    public static final int PREVIEW = 5;
    public static final int PAGE_SIZE = 20;
    /** '5일장', '장날' 같은 말은 시장 이름이 아니라 정기장이 서는 시장을 찾는다 */
    static final Set<String> FAIR_WORDS = Set.of("5일장", "오일장", "장날", "정기장", "닷새장");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NamedParameterJdbcTemplate jdbc;
    private final RegionRepository regionRepository;

    public record Where(Double lat, Double lng) {
        boolean has() {
            return lat != null && lng != null;
        }
    }

    @Transactional(readOnly = true)
    public SearchResult search(SearchQuery q, Where where) {
        return new SearchResult(q.text(), q.words(), regions(q),
                new Group<>(stores(q, where, PREVIEW, 0), countStores(q)),
                new Group<>(markets(q, where, PREVIEW, 0), countMarkets(q)),
                new Group<>(festivals(q, where, PREVIEW, 0), countFestivals(q)),
                new Group<>(posts(q, PREVIEW, 0), countPosts(q)));
    }

    @Transactional(readOnly = true)
    public PageResult<?> page(String type, SearchQuery q, Where where, int page) {
        int p = Math.max(page, 0);
        int offset = p * PAGE_SIZE;
        return switch (type) {
            case "stores" -> paged(stores(q, where, PAGE_SIZE + 1, offset), p, countStores(q));
            case "markets" -> paged(markets(q, where, PAGE_SIZE + 1, offset), p, countMarkets(q));
            case "festivals" -> paged(festivals(q, where, PAGE_SIZE + 1, offset), p, countFestivals(q));
            case "posts" -> paged(posts(q, PAGE_SIZE + 1, offset), p, countPosts(q));
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static <T> PageResult<T> paged(List<T> rows, int page, long total) {
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new PageResult<>(hasNext ? rows.subList(0, PAGE_SIZE) : rows, page, hasNext, total);
    }

    // ---------------------------------------------------------------- 공통 조각

    /** 낱말마다 (칸1 LIKE :w0 OR 칸2 LIKE :w0 ...) 를 AND 로 잇는다 */
    private static String allWords(SearchQuery q, MapSqlParameterSource params, String... columns) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < q.words().size(); i++) {
            String name = "w" + i;
            params.addValue(name, SearchQuery.contains(q.words().get(i)));
            List<String> ors = new ArrayList<>();
            for (String c : columns) ors.add(c + " LIKE :" + name);
            parts.add("(" + String.join(" OR ", ors) + ")");
        }
        return String.join(" AND ", parts);
    }

    /** 이름이 얼마나 잘 맞는지 (작을수록 위) */
    private static String nameScore(String column, SearchQuery q, MapSqlParameterSource params) {
        params.addValue("qExact", q.text());
        params.addValue("qStart", SearchQuery.startsWith(q.text()));
        params.addValue("qAny", SearchQuery.contains(q.text()));
        return "CASE WHEN " + column + " = :qExact THEN 0 WHEN " + column + " LIKE :qStart THEN 1 "
                + "WHEN " + column + " LIKE :qAny THEN 2 ELSE 3 END";
    }

    private static String distance(String alias, Where where, MapSqlParameterSource params) {
        if (!where.has()) return "NULL";
        params.addValue("lat", where.lat());
        params.addValue("lng", where.lng());
        return "ROUND(ST_Distance_Sphere(POINT(" + alias + ".longitude, " + alias + ".latitude), POINT(:lng, :lat)))";
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long n = jdbc.queryForObject(sql, params, Long.class);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- 백년가게

    private String storeWhere(SearchQuery q, MapSqlParameterSource params) {
        // 종류 이름(백년가게·로컬크리에이터)으로도 찾을 수 있게 (LOC-01)
        return "s.is_visible = 1 AND " + allWords(q, params, "s.name", "s.category", "s.road_address", "s.jibun_address",
                "IF(s.type = 'CREATOR', '로컬크리에이터', '백년가게')");
    }

    private List<StoreHit> stores(SearchQuery q, Where where, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        String sql = "SELECT s.id, s.type, s.name, s.category, s.road_address, s.info_status, "
                + distance("s", where, params) + " AS distance_m, " + nameScore("s.name", q, params) + " AS score "
                + "FROM stores s WHERE " + storeWhere(q, params)
                + " ORDER BY score, distance_m IS NULL, distance_m, s.name, s.id LIMIT :limit OFFSET :offset";
        RowMapper<StoreHit> map = (rs, i) -> new StoreHit(rs.getLong("id"), rs.getString("type"), rs.getString("name"), rs.getString("category"),
                rs.getString("road_address"), rs.getString("info_status"), nullableLong(rs, "distance_m"));
        return jdbc.query(sql, params, map);
    }

    private long countStores(SearchQuery q) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return count("SELECT COUNT(*) FROM stores s WHERE " + storeWhere(q, params), params);
    }

    // ---------------------------------------------------------------- 전통시장

    private String marketWhere(SearchQuery q, MapSqlParameterSource params) {
        List<String> parts = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String w : q.words()) {
            if (FAIR_WORDS.contains(w)) parts.add("(m.market_days IS NOT NULL AND m.market_days <> '')");
            else rest.add(w);
        }
        if (!rest.isEmpty()) {
            parts.add(allWords(new SearchQuery(q.text(), rest), params, "m.name", "m.road_address", "m.jibun_address", "m.goods"));
        }
        return String.join(" AND ", parts);
    }

    private List<MarketHit> markets(SearchQuery q, Where where, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        String sql = "SELECT m.id, m.name, m.market_type, m.is_permanent, m.market_days, m.road_address, m.goods, "
                + distance("m", where, params) + " AS distance_m, " + nameScore("m.name", q, params) + " AS score "
                + "FROM markets m WHERE " + marketWhere(q, params)
                + " ORDER BY score, distance_m IS NULL, distance_m, m.name, m.id LIMIT :limit OFFSET :offset";
        RowMapper<MarketHit> map = (rs, i) -> {
            String goods = rs.getString("goods");
            return new MarketHit(rs.getLong("id"), rs.getString("name"), rs.getString("market_type"),
                    rs.getBoolean("is_permanent"), rs.getString("market_days"), rs.getString("road_address"),
                    goods == null || goods.length() <= 80 ? goods : goods.substring(0, 80) + "…",
                    nullableLong(rs, "distance_m"));
        };
        return jdbc.query(sql, params, map);
    }

    private long countMarkets(SearchQuery q) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return count("SELECT COUNT(*) FROM markets m WHERE " + marketWhere(q, params), params);
    }

    // ---------------------------------------------------------------- 축제 (끝난 지 30일 안 된 것까지)

    private String festivalWhere(SearchQuery q, MapSqlParameterSource params) {
        params.addValue("since", Date.valueOf(LocalDate.now(SEOUL).minusDays(30)));
        return "f.end_date >= :since AND " + allWords(q, params, "f.name", "f.place", "f.road_address", "f.jibun_address");
    }

    private List<FestivalHit> festivals(SearchQuery q, Where where, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset)
                .addValue("today", Date.valueOf(LocalDate.now(SEOUL)));
        // 진행 중 → 곧 시작(가까운 날부터) → 최근에 끝남 순, 그 안에서 이름이 잘 맞는 순
        String sql = "SELECT f.id, f.name, f.place, f.start_date, f.end_date, "
                + "CONCAT_WS(' ', r.sido_name, r.sigungu_name) AS region_name, "
                + distance("f", where, params) + " AS distance_m, " + nameScore("f.name", q, params) + " AS score, "
                + "CASE WHEN f.start_date <= :today AND f.end_date >= :today THEN 0 WHEN f.start_date > :today THEN 1 ELSE 2 END AS phase "
                + "FROM festivals f LEFT JOIN regions r ON r.code = f.sigungu_code WHERE " + festivalWhere(q, params)
                + " ORDER BY phase, score, CASE WHEN phase = 2 THEN NULL ELSE f.start_date END, f.end_date DESC, f.id"
                + " LIMIT :limit OFFSET :offset";
        RowMapper<FestivalHit> map = (rs, i) -> new FestivalHit(rs.getLong("id"), rs.getString("name"), rs.getString("place"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                emptyToNull(rs.getString("region_name")), nullableLong(rs, "distance_m"));
        return jdbc.query(sql, params, map);
    }

    private long countFestivals(SearchQuery q) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return count("SELECT COUNT(*) FROM festivals f WHERE " + festivalWhere(q, params), params);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    // ---------------------------------------------------------------- 동네 이야기 (SRCH-02): 보이는 글만

    static final int SNIPPET = 90;

    /** 낱말마다 제목·내용·붙인 곳 이름 중 하나에 들어 있어야 한다. 가려진·지운 글은 빼고 */
    private String postWhere(SearchQuery q, MapSqlParameterSource params) {
        return "p.status = 'VISIBLE' AND " + allWords(q, params, "p.title", "p.content", "COALESCE(s.name, m.name, f.name, '')");
    }

    private static final String POST_FROM = """
             FROM posts p
             JOIN users u ON u.id = p.user_id
             LEFT JOIN stores s ON p.place_type = 'STORE' AND s.id = p.place_id AND s.is_visible = 1
             LEFT JOIN markets m ON p.place_type = 'MARKET' AND m.id = p.place_id
             LEFT JOIN festivals f ON p.place_type = 'FESTIVAL' AND f.id = p.place_id
            """;

    /** 제목이 잘 맞는 글 먼저, 그 안에서는 최근 글 먼저 */
    private List<PostHit> posts(SearchQuery q, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        String sql = "SELECT p.id, p.category, p.title, p.content, p.created_at, p.like_count, p.comment_count, "
                + "u.nickname, u.status AS user_status, COALESCE(s.name, m.name, f.name) AS place_name, "
                + "(SELECT ph.file_key FROM post_photos ph WHERE ph.post_id = p.id ORDER BY ph.sort_order, ph.id LIMIT 1) AS thumb_key, "
                + nameScore("p.title", q, params) + " AS score"
                + POST_FROM + " WHERE " + postWhere(q, params)
                + " ORDER BY score, p.id DESC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, params, (rs, i) -> {
            String cat = rs.getString("category");
            boolean withdrawn = "WITHDRAWN".equals(rs.getString("user_status"));
            return new PostHit(rs.getLong("id"), cat, com.ieum.ieum.community.CommunityDtos.CATEGORIES.get(cat), rs.getString("title"),
                    snippet(rs.getString("content"), q.words()), withdrawn ? "탈퇴한 회원" : rs.getString("nickname"),
                    rs.getString("place_name"), rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getInt("like_count"), rs.getInt("comment_count"),
                    com.ieum.ieum.community.photo.PostPhotoService.thumbUrl(rs.getString("thumb_key")));
        });
    }

    private long countPosts(SearchQuery q) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return count("SELECT COUNT(*)" + POST_FROM + " WHERE " + postWhere(q, params), params);
    }

    /**
     * 내용 한 줄 보기: 검색어가 내용에 있으면 처음 나온 곳의 앞 20자부터, 없으면 맨 앞부터. 줄바꿈은 빈칸으로, 90자까지.
     * (제목·붙인 곳에서만 찾았으면 앞부분)
     */
    static String snippet(String content, List<String> words) {
        String one = content == null ? "" : content.replaceAll("\\s+", " ").strip();
        String lower = one.toLowerCase(java.util.Locale.ROOT);
        int at = -1;
        for (String w : words) {
            int i = lower.indexOf(w.toLowerCase(java.util.Locale.ROOT));
            if (i >= 0 && (at < 0 || i < at)) at = i;
        }
        int start = at <= 20 ? 0 : at - 20;
        // 낱말 가운데서 자르지 않게 빈칸 뒤부터
        if (start > 0) {
            int space = one.indexOf(' ', start);
            if (space >= 0 && space < at) start = space + 1;
        }
        int end = Math.min(one.length(), start + SNIPPET);
        return (start > 0 ? "…" : "") + one.substring(start, end) + (end < one.length() ? "…" : "");
    }

    // ---------------------------------------------------------------- 지역 (가게가 있는 시·군·구, 230여 곳이라 메모리에서 찾음)

    private List<RegionHit> regions(SearchQuery q) {
        return regionRepository.findAllWithCenter().stream()
                .filter(r -> q.words().stream().allMatch(w -> (r.getSidoName() + " " + r.getSigunguName()).contains(w)))
                .sorted(Comparator.comparing((RegionRepository.RegionRow r) -> r.getSigunguName().startsWith(q.words().get(0)) ? 0 : 1)
                        .thenComparing(r -> -r.getStoreCount()))
                .limit(PREVIEW)
                .map(r -> new RegionHit(r.getCode(), r.getSidoName() + " " + r.getSigunguName(),
                        r.getCenterLat(), r.getCenterLng(), r.getStoreCount()))
                .toList();
    }
}
