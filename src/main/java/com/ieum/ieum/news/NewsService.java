package com.ieum.ieum.news;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.news.NaverNewsClient.Article;
import com.ieum.ieum.news.NaverNewsClient.NewsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 동네 소식 (NEWS-01): 네이버 뉴스 검색 결과의 제목·출처(도메인)·날짜·원문 링크만 보여 준다.
 *
 * - 검색어는 서버가 정한다 (주제 3개 + 시장 이름). 아무 검색어나 받지 않아서 호출 수가 늘지 않는다.
 * - 같은 검색은 서버 메모리에 3시간(주제)·6시간(시장) 두고 다시 쓴다. 네이버가 실패하면 예전 결과가 있으면 그것을, 없으면 빈 목록.
 * - 실패하면 5분 동안 부르지 않는다. 하루 호출 수는 naver.daily-limit(기본 20,000, 네이버 검색 API 하루 25,000건 공용) 안에서.
 * - 관계없는 기사를 줄이려고 거른다. 주제 기사는 제목에 주제어가 있는 것만 (요약에만 한 번 나오는 기사가 많아서),
 *   시장 기사는 제목이나 요약에 시장 이름이 있는 것만 남긴다.
 *   이름이 흔한 시장(같은 이름이 여러 곳, 예: 중앙시장)은 지역 이름을 붙여 찾고, 지역 이름도 들어 있어야 남긴다.
 * - 같은 기사가 여러 언론사에 실린 경우(제목이 같음) 하나만 남긴다.
 */
@Slf4j
@Service
public class NewsService {

    public enum Topic {
        MARKET("market", "전통시장", "전통시장"),
        STORE("store", "백년가게", "백년가게"),
        LOCAL("local", "로컬크리에이터", "로컬크리에이터");

        public final String key;
        public final String label;
        public final String query;

        Topic(String key, String label, String query) {
            this.key = key;
            this.label = label;
            this.query = query;
        }

        public static Topic of(String key) {
            for (Topic t : values()) if (t.key.equals(key)) return t;
            throw new BusinessException(ErrorCode.INVALID_INPUT, List.of("주제는 market, store, local 중 하나예요."));
        }
    }

    public record NewsItem(String title, String url, String host, LocalDateTime publishedAt) {
    }

    public record Result(String query, List<NewsItem> items, boolean available) {
    }

    private record Cached(List<Article> articles, Instant at) {
    }

    static final int FETCH = 50;
    static final Duration TOPIC_TIME = Duration.ofHours(3);
    static final Duration MARKET_TIME = Duration.ofHours(6);
    static final Duration PAUSE_AFTER_FAILURE = Duration.ofMinutes(5);
    static final int CACHE_SIZE = 2000;
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final NaverNewsClient client;
    private final NamedParameterJdbcTemplate jdbc;
    private final int dailyLimit;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile Instant pausedUntil = Instant.EPOCH;
    private LocalDate countDay = LocalDate.now(SEOUL);
    private int countToday = 0;

    public NewsService(NaverNewsClient client, NamedParameterJdbcTemplate jdbc, @Value("${naver.daily-limit:20000}") int dailyLimit) {
        this.client = client;
        this.jdbc = jdbc;
        this.dailyLimit = dailyLimit;
    }

    public Result topic(Topic topic, int size) {
        Fetched f = fetch(topic.query, TOPIC_TIME);
        return new Result(topic.query, pick(f.articles(), List.of(topic.query), true, size), f.available());
    }

    public Result market(long marketId, int size) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT m.name, r.sido_name, r.sigungu_name,
                       (SELECT COUNT(*) FROM markets x WHERE x.name = m.name) AS same
                FROM markets m LEFT JOIN regions r ON r.code = m.sigungu_code
                WHERE m.id = :id
                """, new MapSqlParameterSource("id", marketId));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.MARKET_NOT_FOUND);
        Map<String, Object> r = rows.getFirst();
        String name = ((String) r.get("name")).strip();
        long same = ((Number) r.get("same")).longValue();
        String region = regionWord((String) r.get("sido_name"), (String) r.get("sigungu_name"));
        boolean common = same > 1 || name.replace(" ", "").length() <= 4;
        List<String> must = new ArrayList<>(List.of(name));
        String query = name;
        if (common && region != null) {
            query = region + " " + name;
            must.add(region);
        }
        Fetched f = fetch(query, MARKET_TIME);
        return new Result(query, pick(f.articles(), must, false, size), f.available());
    }

    /**
     * 기사에서 찾을 지역 이름: 시·군은 '김천'처럼 끝 글자를 뺀 이름, 광역시·특별시의 구는 '대구'처럼 시도 이름
     * (중구·동구는 여러 도시에 있어서)
     */
    static String regionWord(String sido, String sigungu) {
        if (sigungu != null && !sigungu.isBlank()) {
            String first = sigungu.strip().split(" ")[0];          // '성남시 분당구' → '성남시'
            if ((first.endsWith("시") || first.endsWith("군")) && first.length() >= 3) return first.substring(0, first.length() - 1);
        }
        if (sido == null || sido.isBlank()) return null;
        String s = sido.strip();
        for (String suffix : List.of("특별자치시", "특별자치도", "특별시", "광역시")) {
            if (s.endsWith(suffix)) return s.substring(0, s.length() - suffix.length());
        }
        return switch (s) {
            case "경상북도" -> "경북";
            case "경상남도" -> "경남";
            case "전라남도" -> "전남";
            case "충청북도" -> "충북";
            case "충청남도" -> "충남";
            case "경기도" -> "경기";
            default -> s.endsWith("도") ? s.substring(0, s.length() - 1) : s;
        };
    }

    private record Fetched(List<Article> articles, boolean available) {
    }

    private Fetched fetch(String query, Duration keep) {
        String key = query.toLowerCase(Locale.ROOT);
        Cached old = cached(key);
        if (old != null && old.at().plus(keep).isAfter(Instant.now())) return new Fetched(old.articles(), true);
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            old = cached(key);
            if (old != null && old.at().plus(keep).isAfter(Instant.now())) return new Fetched(old.articles(), true);
            if (!client.hasKey() || Instant.now().isBefore(pausedUntil) || !takeQuota()) {
                return old != null ? new Fetched(old.articles(), true) : new Fetched(List.of(), false);
            }
            try {
                List<Article> got = client.search(query, FETCH, true);
                synchronized (cache) {
                    cache.put(key, new Cached(got, Instant.now()));
                }
                return new Fetched(got, true);
            } catch (NewsException e) {
                pausedUntil = Instant.now().plus(PAUSE_AFTER_FAILURE);
                log.warn("{} ({}) → {}분 동안 다시 부르지 않음", e.getMessage(), query, PAUSE_AFTER_FAILURE.toMinutes());
                return old != null ? new Fetched(old.articles(), true) : new Fetched(List.of(), false);
            }
        }
    }

    private Cached cached(String key) {
        synchronized (cache) {
            return cache.get(key);
        }
    }

    private synchronized boolean takeQuota() {
        LocalDate today = LocalDate.now(SEOUL);
        if (!today.equals(countDay)) {
            countDay = today;
            countToday = 0;
        }
        if (countToday >= dailyLimit) return false;
        countToday++;
        return true;
    }

    /** 이름이 들어 있는 기사만(titleOnly 면 제목에, 아니면 제목이나 요약에), 같은 제목은 하나만, 최신순으로 size개 */
    static List<NewsItem> pick(List<Article> articles, List<String> must, boolean titleOnly, int size) {
        List<String> words = must.stream().map(NewsService::squash).toList();
        Set<String> seen = new HashSet<>();
        List<NewsItem> out = new ArrayList<>();
        for (Article a : articles) {
            String text = squash(titleOnly ? a.title() : a.title() + " " + a.description());
            if (!words.stream().allMatch(text::contains)) continue;
            if (!seen.add(squash(a.title())) || !seen.add(a.url())) continue;
            out.add(new NewsItem(a.title(), a.url(), a.host(), a.publishedAt()));
        }
        out.sort((x, y) -> {
            if (x.publishedAt() == null) return y.publishedAt() == null ? 0 : 1;
            if (y.publishedAt() == null) return -1;
            return y.publishedAt().compareTo(x.publishedAt());
        });
        return out.size() > size ? List.copyOf(out.subList(0, size)) : out;
    }

    private static String squash(String s) {
        return s.replaceAll("[\\s\\p{Punct}·'\"“”‘’]", "").toLowerCase(Locale.ROOT);
    }
}
