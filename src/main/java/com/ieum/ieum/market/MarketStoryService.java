package com.ieum.ieum.market;

import com.ieum.ieum.festival.PublicDataClient;
import com.ieum.ieum.festival.PublicDataClient.TourSpotRecord;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시장 이야기 (MKT-02)
 *
 * 1) 시장 이야기 (market_stories): 사람이 확인한 자료로 정리한 역사·유래. 출처가 1개 이상 있어야 저장되고, 공개(PUBLISHED)한 글만 보인다.
 *    AI가 지어낸 내용은 넣지 않는다. 22번 SQL의 초안도 출처에서 확인한 사실만 담았고, 관리자가 확인한 뒤 공개한다.
 * 2) 한국관광공사 소개글 (market_tour_info): 관광공사 쇼핑(38) 정보 가운데 시장 근처 700m 안에서 이름이 맞는 곳의 개요.
 *    처음 볼 때 불러와 저장하고 30일 동안 다시 쓴다. 실패하면 1시간 뒤 다시 시도. 원문은 바꾸지 않고 줄바꿈만 정리한다.
 */
@Slf4j
@Service
public class MarketStoryService {

    public static final String TOUR_SOURCE = "한국관광공사 국문 관광정보";
    static final int SHOPPING = 38;
    static final int MATCH_RADIUS = 700;
    static final Duration KEEP = Duration.ofDays(30);
    static final Duration RETRY = Duration.ofHours(1);
    public static final int BODY_MIN = 30;
    public static final int BODY_MAX = 3000;
    public static final int SOURCES_MAX = 8;

    public record Source(String title, String url) {
    }

    public record Story(String body, List<Source> sources, String status, String origin, LocalDateTime updatedAt,
                        LocalDateTime publishedAt, boolean edited) {
    }

    public record TourInfo(String title, String overview, String contentId, String source) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final PublicDataClient client;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public MarketStoryService(NamedParameterJdbcTemplate jdbc, PublicDataClient client, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.client = client;
        this.tx = tx;
    }

    // ---------------------------------------------------------------- 읽기

    public Optional<Story> story(long marketId, boolean publishedOnly) {
        List<Story> rows = jdbc.query("""
                SELECT body, sources, status, origin, updated_at, published_at, updated_by FROM market_stories
                WHERE market_id = :id AND (:all = TRUE OR status = 'PUBLISHED')
                """, new MapSqlParameterSource("id", marketId).addValue("all", !publishedOnly), (rs, i) -> new Story(
                rs.getString("body"), parseSources(rs.getString("sources")), rs.getString("status"), rs.getString("origin"),
                rs.getObject("updated_at", LocalDateTime.class), rs.getObject("published_at", LocalDateTime.class),
                rs.getObject("updated_by") != null));
        return rows.stream().findFirst();
    }

    List<Source> parseSources(String text) {
        try {
            return json.readValue(text, new TypeReference<List<Source>>() {
            });
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 관광공사 소개글. 저장된 것이 없거나 오래됐으면 불러온다. available=false 면 지금 불러오지 못함 */
    public record TourResult(Optional<TourInfo> info, boolean available) {
    }

    public TourResult tour(long marketId) {
        Map<String, Object> m = market(marketId);
        if (m.get("latitude") == null || m.get("longitude") == null) return new TourResult(Optional.empty(), true);
        synchronized (locks.computeIfAbsent(marketId, k -> new Object())) {
            // 저장 시각(NOW())과 비교도 DB 시계로 한다 (서버와 DB의 시간대가 달라도 맞게)
            List<Map<String, Object>> saved = jdbc.queryForList("""
                    SELECT status, content_id, title, overview,
                           fetched_at > NOW() - INTERVAL IF(status = 'FAILED', :retry, :keep) MINUTE AS fresh
                    FROM market_tour_info WHERE market_id = :id
                    """, new MapSqlParameterSource("id", marketId).addValue("retry", RETRY.toMinutes()).addValue("keep", KEEP.toMinutes()));
            if (!saved.isEmpty()) {
                Map<String, Object> r = saved.getFirst();
                String status = (String) r.get("status");
                boolean fresh = ((Number) r.get("fresh")).intValue() == 1;
                if (fresh) {
                    if ("FOUND".equals(status)) {
                        return new TourResult(Optional.of(new TourInfo((String) r.get("title"), (String) r.get("overview"),
                                (String) r.get("content_id"), TOUR_SOURCE)), true);
                    }
                    return new TourResult(Optional.empty(), !"FAILED".equals(status));
                }
            }
            return fetchTour(marketId, (String) m.get("name"), ((Number) m.get("latitude")).doubleValue(),
                    ((Number) m.get("longitude")).doubleValue());
        }
    }

    private TourResult fetchTour(long marketId, String name, double lat, double lng) {
        try {
            List<TourSpotRecord> spots = client.nearbySpots(lat, lng, MATCH_RADIUS, SHOPPING, 1);
            Optional<TourSpotRecord> match = spots.stream().filter(s -> matchRank(name, s.title()) >= 0)
                    .min(Comparator.<TourSpotRecord>comparingInt(s -> matchRank(name, s.title()))
                            .thenComparingDouble(s -> distance(lat, lng, s.latitude(), s.longitude())));
            if (match.isEmpty()) {
                saveTour(marketId, "NONE", null, null, null);
                return new TourResult(Optional.empty(), true);
            }
            TourSpotRecord spot = match.get();
            String overview = client.tourDetail(spot.contentId(), true).map(PublicDataClient.TourDetail::overview)
                    .filter(o -> o != null && !o.isBlank()).orElse(null);
            if (overview == null) {
                saveTour(marketId, "NONE", spot.contentId(), spot.title(), null);
                return new TourResult(Optional.empty(), true);
            }
            saveTour(marketId, "FOUND", spot.contentId(), spot.title(), overview);
            return new TourResult(Optional.of(new TourInfo(spot.title(), overview, spot.contentId(), TOUR_SOURCE)), true);
        } catch (PublicDataClient.PublicDataException e) {
            log.warn("시장 {} 관광공사 소개글 불러오기 실패: {}", marketId, e.getMessage());
            saveTour(marketId, "FAILED", null, null, null);
            return new TourResult(Optional.empty(), false);
        }
    }

    private void saveTour(long marketId, String status, String contentId, String title, String overview) {
        jdbc.update("""
                INSERT INTO market_tour_info (market_id, status, content_id, title, overview, fetched_at)
                VALUES (:id, :status, :cid, :title, :overview, NOW())
                ON DUPLICATE KEY UPDATE status = VALUES(status), content_id = VALUES(content_id), title = VALUES(title),
                                        overview = VALUES(overview), fetched_at = VALUES(fetched_at)
                """, new MapSqlParameterSource().addValue("id", marketId).addValue("status", status)
                .addValue("cid", contentId).addValue("title", title).addValue("overview", overview));
    }

    /**
     * 관광공사 이름이 시장과 같은 곳인지와 얼마나 잘 맞는지 (작을수록 잘 맞음, -1 이면 다른 곳).
     * 괄호·(주)·'전통'·'재래'·띄어쓰기를 빼고 비교한다.
     *   0: 같음            '통영중앙전통시장' ↔ '통영중앙시장', '1913송정역시장(송정역전매일시장)' ↔ '1913송정역시장'
     *   1: 시장 이름이 더 김  '속초관광수산시장(구 속초중앙전통시장)' ↔ '속초관광수산시장'
     *   2: 관광공사 이름이 조금 더 김 (앞뒤 4자까지, 지역 이름 등)  '중앙시장'(강릉) ↔ '강릉중앙시장'
     * '남대문시장 근처 상점가', '광장시장 먹자골목'처럼 시장 이름에 다른 말이 많이 붙은 곳은 그 시장 소개가 아니라고 본다.
     */
    static int matchRank(String marketName, String tourTitle) {
        String a = core(marketName);
        String b = core(tourTitle);
        if (a.length() < 3 || b.length() < 3) return -1;
        if (a.equals(b)) return 0;
        if (a.contains(b) && b.endsWith("시장")) return 1;
        if (b.contains(a) && b.length() - a.length() <= 4 && b.endsWith(a)) return 2;
        return -1;
    }

    static String core(String s) {
        if (s == null) return "";
        return s.replaceAll("\\([^)]*\\)", "").replace("㈜", "").replace("(주)", "")
                .replace("전통", "").replace("재래", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static double distance(double lat1, double lng1, Double lat2, Double lng2) {
        if (lat2 == null || lng2 == null) return Double.MAX_VALUE;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double h = Math.pow(Math.sin((p2 - p1) / 2), 2) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(lng2 - lng1) / 2), 2);
        return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    Map<String, Object> market(long marketId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, road_address, latitude, longitude FROM markets WHERE id = :id",
                new MapSqlParameterSource("id", marketId));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
        return rows.getFirst();
    }

    // ---------------------------------------------------------------- 관리자

    public record StoryRow(long marketId, String marketName, String address, String status, String origin, boolean edited,
                           LocalDateTime updatedAt, LocalDateTime publishedAt, int sourceCount, String preview) {
    }

    public record StoryPage(List<StoryRow> items, long total, int page, boolean hasNext, Map<String, Long> counts) {
    }

    public StoryPage list(String status, int page) {
        if (!List.of("ALL", "DRAFT", "PUBLISHED", "HIDDEN").contains(status)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("status 값을 확인해 주세요."));
        }
        int size = 20;
        MapSqlParameterSource p = new MapSqlParameterSource("status", status).addValue("limit", size + 1)
                .addValue("offset", Math.max(0, page) * size);
        String where = "(:status = 'ALL' OR s.status = :status)";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM market_stories s WHERE " + where, p, Long.class);
        List<StoryRow> rows = new ArrayList<>(jdbc.query("""
                SELECT s.market_id, m.name, m.road_address, s.status, s.origin, s.updated_by, s.updated_at, s.published_at,
                       JSON_LENGTH(s.sources) AS n, LEFT(s.body, 80) AS preview
                FROM market_stories s JOIN markets m ON m.id = s.market_id
                WHERE\s""" + where + """
                ORDER BY FIELD(s.status, 'DRAFT', 'PUBLISHED', 'HIDDEN'), s.updated_at DESC, s.market_id
                LIMIT :limit OFFSET :offset
                """, p, (rs, i) -> new StoryRow(rs.getLong("market_id"), rs.getString("name"), rs.getString("road_address"),
                rs.getString("status"), rs.getString("origin"), rs.getObject("updated_by") != null,
                rs.getObject("updated_at", LocalDateTime.class), rs.getObject("published_at", LocalDateTime.class),
                rs.getInt("n"), rs.getString("preview"))));
        boolean hasNext = rows.size() > size;
        if (hasNext) rows.removeLast();
        Map<String, Long> counts = new java.util.LinkedHashMap<>(Map.of("DRAFT", 0L, "PUBLISHED", 0L, "HIDDEN", 0L));
        jdbc.query("SELECT status, COUNT(*) AS n FROM market_stories GROUP BY status", new MapSqlParameterSource(),
                rs -> { counts.put(rs.getString("status"), rs.getLong("n")); });
        return new StoryPage(rows, total == null ? 0 : total, page, hasNext, counts);
    }

    public record MarketHit(long id, String name, String address, String storyStatus) {
    }

    /** 이야기를 새로 쓸 시장 찾기 (이름, 20곳) */
    public List<MarketHit> searchMarkets(String q) {
        String query = q == null ? "" : q.strip();
        if (query.length() < 2 || query.length() > 30) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("시장 이름을 2~30자로 넣어 주세요."));
        }
        return jdbc.query("""
                SELECT m.id, m.name, m.road_address, s.status FROM markets m LEFT JOIN market_stories s ON s.market_id = m.id
                WHERE REPLACE(m.name, ' ', '') LIKE CONCAT('%', REPLACE(:q, ' ', ''), '%')
                ORDER BY (m.name = :q) DESC, m.store_count DESC, m.id LIMIT 20
                """, new MapSqlParameterSource("q", query), (rs, i) -> new MarketHit(rs.getLong("id"), rs.getString("name"),
                rs.getString("road_address"), rs.getString("status")));
    }

    public record StoryRequest(String body, List<Source> sources) {
    }

    /** 저장. 새 글은 초안(DRAFT), 있던 글은 상태를 그대로 두고 내용만 바꾼다 */
    public Story save(long adminId, long marketId, StoryRequest req) {
        market(marketId);
        String body = req == null || req.body() == null ? "" : req.body().replace("\r\n", "\n").strip();
        List<Source> sources = validate(body, req == null ? null : req.sources());
        String sourcesJson = json.writeValueAsString(sources);
        tx.executeWithoutResult(t -> {
            jdbc.queryForList("SELECT market_id FROM market_stories WHERE market_id = :id FOR UPDATE", new MapSqlParameterSource("id", marketId));
            jdbc.update("""
                    INSERT INTO market_stories (market_id, body, sources, status, origin, updated_by)
                    VALUES (:id, :body, CAST(:sources AS JSON), 'DRAFT', 'ADMIN', :admin)
                    ON DUPLICATE KEY UPDATE body = VALUES(body), sources = VALUES(sources), updated_by = VALUES(updated_by)
                    """, new MapSqlParameterSource().addValue("id", marketId).addValue("body", body)
                    .addValue("sources", sourcesJson).addValue("admin", adminId));
        });
        return story(marketId, false).orElseThrow();
    }

    List<Source> validate(String body, List<Source> sources) {
        List<String> errors = new ArrayList<>();
        if (body.length() < BODY_MIN || body.length() > BODY_MAX) errors.add("본문은 " + BODY_MIN + "~" + BODY_MAX + "자로 써 주세요.");
        List<Source> clean = new ArrayList<>();
        if (sources != null) {
            for (Source s : sources) {
                String title = s == null || s.title() == null ? "" : s.title().strip();
                String url = s == null || s.url() == null ? "" : s.url().strip();
                if (title.isEmpty() && url.isEmpty()) continue;     // 빈 줄은 무시
                if (title.isEmpty() || title.length() > 100) errors.add("출처 이름은 1~100자로 써 주세요.");
                if (!validUrl(url)) errors.add("출처 링크는 http:// 나 https:// 로 시작하는 주소로 써 주세요 (500자까지).");
                clean.add(new Source(title, url));
            }
        }
        if (clean.isEmpty()) errors.add("출처를 1개 이상 달아 주세요. 확인한 자료의 이름과 링크가 있어야 저장할 수 있어요.");
        if (clean.size() > SOURCES_MAX) errors.add("출처는 " + SOURCES_MAX + "개까지 달 수 있어요.");
        if (!errors.isEmpty()) throw new BusinessException(ErrorCode.INVALID_INPUT, errors.stream().distinct().toList());
        return clean;
    }

    static boolean validUrl(String url) {
        if (url.isEmpty() || url.length() > 500) return false;
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && u.getHost() != null && u.getHost().contains(".");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 공개 / 숨김 */
    public Story setStatus(long adminId, long marketId, String status) {
        tx.executeWithoutResult(t -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status FROM market_stories WHERE market_id = :id FOR UPDATE", new MapSqlParameterSource("id", marketId));
            if (rows.isEmpty()) throw new BusinessException(ErrorCode.STORY_NOT_FOUND);
            jdbc.update("""
                    UPDATE market_stories SET status = :status, updated_by = :admin,
                           published_at = IF(:status = 'PUBLISHED', NOW(), published_at)
                    WHERE market_id = :id
                    """, new MapSqlParameterSource().addValue("id", marketId).addValue("status", status).addValue("admin", adminId));
        });
        return story(marketId, false).orElseThrow();
    }
}
