package com.ieum.ieum.theme;

import com.ieum.ieum.theme.ThemeDtos.AlleyItem;
import com.ieum.ieum.theme.ThemeDtos.MarketLite;
import com.ieum.ieum.theme.ThemeDtos.MarketStoresItem;
import com.ieum.ieum.theme.ThemeDtos.StoreLite;
import com.ieum.ieum.theme.ThemeDtos.ThemePage;
import com.ieum.ieum.theme.ThemeDtos.ThemeSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/**
 * 테마로 걷기 (THM-01): 지금 있는 데이터로 계산하는 테마 3가지
 *
 * 1. alleys         백년가게 골목     — 서로 150m 안으로 이어진 백년가게가 3곳 이상인 곳
 * 2. market-stores  시장 안 백년가게  — 전통시장에서 300m 안에 있는 백년가게
 * 3. fair-markets   장날 따라 걷는 5일장 — 정기장(5일장 등)이 서는 시장
 *
 * 가게·시장이 수천 곳이라 DB에서 한 번 읽어 메모리에서 계산하고, 10분 동안 그대로 쓴다.
 * (관리자가 가게를 폐업 처리하면 늦어도 10분 뒤 테마에서도 빠진다)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeService {

    public static final int ALLEY_LINK_M = 150;
    public static final int ALLEY_MIN_STORES = 3;
    public static final int MARKET_STORE_M = 300;
    public static final int NEAR_M = 30_000;
    public static final int PAGE_SIZE = 20;
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final double EARTH_R = 6_371_008.8;

    private final NamedParameterJdbcTemplate jdbc;
    private volatile Snapshot snapshot;

    public record Where(Double lat, Double lng) {
        boolean has() {
            return lat != null && lng != null;
        }
    }

    // ---------------------------------------------------------------- 읽어 온 데이터

    record Store(long id, String name, String category, String road, double lat, double lng, String region) {
    }

    record Market(long id, String name, String road, double lat, double lng, String days, boolean permanent, String region) {
    }

    record Alley(String key, String label, String region, double lat, double lng, int spanM, List<Store> stores) {
    }

    record MarketStores(Market market, List<Store> stores) {
    }

    record Snapshot(List<Alley> alleys, List<MarketStores> marketStores, List<Market> fairs, Instant at) {
    }

    private Snapshot data() {
        Snapshot s = snapshot;
        if (s == null || s.at().plus(TTL).isBefore(Instant.now())) {
            synchronized (this) {
                s = snapshot;
                if (s == null || s.at().plus(TTL).isBefore(Instant.now())) {
                    s = build();
                    snapshot = s;
                }
            }
        }
        return s;
    }

    /** 다음 요청 때 다시 계산하게 한다 (테스트, 데이터를 크게 바꾼 뒤) */
    public void refresh() {
        snapshot = null;
    }

    private Snapshot build() {
        long started = System.currentTimeMillis();
        Map<String, String> regions = new HashMap<>();
        jdbc.query("SELECT code, sido_name, sigungu_name FROM regions", rs -> {
            regions.put(rs.getString("code"), rs.getString("sido_name") + " " + rs.getString("sigungu_name"));
        });
        List<Store> stores = jdbc.query("""
                SELECT id, name, category, road_address, latitude, longitude, sigungu_code
                FROM stores WHERE is_visible = 1 AND latitude IS NOT NULL AND longitude IS NOT NULL
                """, new MapSqlParameterSource(), (rs, i) -> new Store(rs.getLong("id"), rs.getString("name"),
                rs.getString("category"), rs.getString("road_address"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                regions.get(rs.getString("sigungu_code"))));
        List<Market> markets = jdbc.query("""
                SELECT id, name, road_address, latitude, longitude, market_days, is_permanent, sigungu_code
                FROM markets WHERE latitude IS NOT NULL AND longitude IS NOT NULL
                """, new MapSqlParameterSource(), (rs, i) -> new Market(rs.getLong("id"), rs.getString("name"),
                rs.getString("road_address"), rs.getDouble("latitude"), rs.getDouble("longitude"),
                rs.getString("market_days"), rs.getBoolean("is_permanent"), regions.get(rs.getString("sigungu_code"))));

        List<MarketStores> marketStores = marketStores(markets, stores);
        List<Alley> alleys = alleys(stores, markets);
        List<Market> fairs = markets.stream().filter(m -> !digits(m.days()).isEmpty()).toList();
        log.info("테마 계산: 골목 {}곳, 시장 안 백년가게 {}곳, 5일장 {}곳 ({}ms)",
                alleys.size(), marketStores.size(), fairs.size(), System.currentTimeMillis() - started);
        return new Snapshot(alleys, marketStores, fairs, Instant.now());
    }

    // ---------------------------------------------------------------- 계산

    static double distance(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_R * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** 위도 차이가 이만큼보다 크면 거리를 계산할 필요가 없다 (1도 ≈ 111km) */
    private static double latWindow(int meters) {
        return meters / 111_000.0 + 0.0005;
    }

    private static List<MarketStores> marketStores(List<Market> markets, List<Store> stores) {
        List<Store> byLat = stores.stream().sorted(Comparator.comparingDouble(Store::lat)).toList();
        double[] lats = byLat.stream().mapToDouble(Store::lat).toArray();
        double w = latWindow(MARKET_STORE_M);
        List<MarketStores> result = new ArrayList<>();
        for (Market m : markets) {
            List<Store> near = new ArrayList<>();
            for (int i = lowerBound(lats, m.lat() - w); i < lats.length && lats[i] <= m.lat() + w; i++) {
                Store s = byLat.get(i);
                if (distance(m.lat(), m.lng(), s.lat(), s.lng()) <= MARKET_STORE_M) near.add(s);
            }
            if (!near.isEmpty()) {
                near.sort(Comparator.comparingDouble(s -> distance(m.lat(), m.lng(), s.lat(), s.lng())));
                result.add(new MarketStores(m, List.copyOf(near)));
            }
        }
        return result;
    }

    /** 가게를 점으로 보고, 150m 안의 가게끼리 이어서 묶는다 (이어진 묶음이 3곳 이상이면 골목) */
    private static List<Alley> alleys(List<Store> stores, List<Market> markets) {
        List<Store> byLat = stores.stream().sorted(Comparator.comparingDouble(Store::lat)).toList();
        int n = byLat.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        double w = latWindow(ALLEY_LINK_M);
        for (int i = 0; i < n; i++) {
            Store a = byLat.get(i);
            for (int j = i + 1; j < n && byLat.get(j).lat() - a.lat() <= w; j++) {
                Store b = byLat.get(j);
                if (distance(a.lat(), a.lng(), b.lat(), b.lng()) <= ALLEY_LINK_M) union(parent, i, j);
            }
        }
        Map<Integer, List<Store>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(byLat.get(i));

        List<Alley> result = new ArrayList<>();
        for (List<Store> g : groups.values()) {
            if (g.size() < ALLEY_MIN_STORES) continue;
            double lat = g.stream().mapToDouble(Store::lat).average().orElseThrow();
            double lng = g.stream().mapToDouble(Store::lng).average().orElseThrow();
            int span = 0;
            for (Store a : g) for (Store b : g) span = Math.max(span, (int) Math.round(distance(a.lat(), a.lng(), b.lat(), b.lng())));
            List<Store> sorted = g.stream().sorted(Comparator.comparingDouble(s -> distance(lat, lng, s.lat(), s.lng()))).toList();
            String key = "a" + g.stream().mapToLong(Store::id).min().orElseThrow();
            result.add(new Alley(key, alleyLabel(g, lat, lng, markets), mostCommon(g, Store::region), lat, lng, span, sorted));
        }
        result.sort(Comparator.comparingInt((Alley a) -> -a.stores().size()).thenComparing(Alley::key));
        return result;
    }

    /** 골목 이름: 가운데에서 300m 안에 시장이 있으면 '○○시장 일대', 없으면 가장 많은 길 이름 + 일대 */
    private static String alleyLabel(List<Store> g, double lat, double lng, List<Market> markets) {
        Market nearest = null;
        double best = MARKET_STORE_M;
        for (Market m : markets) {
            double d = distance(lat, lng, m.lat(), m.lng());
            if (d <= best) { best = d; nearest = m; }
        }
        if (nearest != null) return nearest.name() + " 일대";
        String road = mostCommon(g, s -> roadName(s.road()));
        return road == null ? "백년가게 골목" : road + " 일대";
    }

    /** '서울 중구 남대문시장길 22-2' → '중구 남대문시장길' (시·도와 건물 번호를 뺀 길 이름) */
    static String roadName(String address) {
        if (address == null || address.isBlank()) return null;
        String[] t = address.trim().split("\\s+");
        int end = t.length;
        if (end > 1 && t[end - 1].matches("^(지하)?\\d.*")) end--;
        int start = Math.min(1, end - 1);
        return end - start <= 0 ? null : String.join(" ", Arrays.copyOfRange(t, start, end));
    }

    private static <T> String mostCommon(List<T> items, Function<T, String> key) {
        return items.stream().map(key).filter(k -> k != null && !k.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }

    private static void union(int[] p, int a, int b) {
        p[find(p, a)] = find(p, b);
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0, hi = a.length;
        while (lo < hi) { int mid = (lo + hi) >>> 1; if (a[mid] < v) lo = mid + 1; else hi = mid; }
        return lo;
    }

    /** '1,6' → [1, 6] (market-days.js의 digitsOf와 같음) */
    static List<Integer> digits(String days) {
        if (days == null) return List.of();
        return Arrays.stream(days.split(",")).map(String::trim).filter(d -> d.matches("\\d")).map(Integer::valueOf).toList();
    }

    /** 오늘 장날인지 (market-days.js의 isMarketDay와 같음: 31일은 장날로 보지 않음) */
    static boolean isFairDay(String days, LocalDate date) {
        int d = date.getDayOfMonth();
        return d <= 30 && digits(days).contains(d % 10);
    }

    // ---------------------------------------------------------------- API

    public List<ThemeSummary> summaries(Where where) {
        Snapshot s = data();
        return List.of(summary("alleys", s, where), summary("market-stores", s, where), summary("fair-markets", s, where));
    }

    private ThemeSummary summary(String id, Snapshot s, Where where) {
        LocalDate today = LocalDate.now(SEOUL);
        return switch (id) {
            case "alleys" -> new ThemeSummary(id, "백년가게 골목",
                    "걸어서 몇 분 거리에 오래된 가게가 모여 있는 골목", "곳", s.alleys().size(),
                    nearCount(s.alleys(), Alley::lat, Alley::lng, where), null);
            case "market-stores" -> new ThemeSummary(id, "시장 안 백년가게",
                    "장도 보고 오래된 가게도 만나는 전통시장", "곳", s.marketStores().size(),
                    nearCount(s.marketStores(), ms -> ms.market().lat(), ms -> ms.market().lng(), where), null);
            case "fair-markets" -> new ThemeSummary(id, "장날 따라 걷는 5일장",
                    "날짜 끝자리로 장이 서는 전국의 정기장", "곳", s.fairs().size(),
                    nearCount(s.fairs(), Market::lat, Market::lng, where),
                    s.fairs().stream().filter(m -> isFairDay(m.days(), today)).count());
            default -> throw new IllegalArgumentException(id);
        };
    }

    private static <T> Long nearCount(List<T> items, ToDoubleFunction<T> lat, ToDoubleFunction<T> lng, Where where) {
        if (!where.has()) return null;
        return items.stream().filter(i -> distance(where.lat(), where.lng(), lat.applyAsDouble(i), lng.applyAsDouble(i)) <= NEAR_M).count();
    }

    public ThemePage<AlleyItem> alleys(Where where, int page) {
        Snapshot s = data();
        List<Alley> list = new ArrayList<>(s.alleys());
        if (where.has()) list.sort(Comparator.comparingDouble(a -> distance(where.lat(), where.lng(), a.lat(), a.lng())));
        return page(summary("alleys", s, where), list, page, a -> new AlleyItem(a.key(), a.label(), a.region(), a.lat(), a.lng(),
                a.spanM(), dist(where, a.lat(), a.lng()),
                a.stores().stream().map(st -> store(st, a.lat(), a.lng())).toList()));
    }

    public ThemePage<MarketStoresItem> marketStores(Where where, int page) {
        Snapshot s = data();
        LocalDate today = LocalDate.now(SEOUL);
        List<MarketStores> list = new ArrayList<>(s.marketStores());
        if (where.has()) {
            list.sort(Comparator.comparingDouble(ms -> distance(where.lat(), where.lng(), ms.market().lat(), ms.market().lng())));
        } else {
            list.sort(Comparator.comparingInt((MarketStores ms) -> -ms.stores().size()).thenComparing(ms -> ms.market().name()));
        }
        return page(summary("market-stores", s, where), list, page, ms -> new MarketStoresItem(market(ms.market(), where, today),
                ms.stores().stream().map(st -> store(st, ms.market().lat(), ms.market().lng())).toList()));
    }

    public ThemePage<MarketLite> fairMarkets(Where where, int page, boolean todayOnly) {
        Snapshot s = data();
        LocalDate today = LocalDate.now(SEOUL);
        List<Market> list = new ArrayList<>(todayOnly ? s.fairs().stream().filter(m -> isFairDay(m.days(), today)).toList() : s.fairs());
        if (where.has()) {
            list.sort(Comparator.comparingDouble(m -> distance(where.lat(), where.lng(), m.lat(), m.lng())));
        } else {
            list.sort(Comparator.comparing((Market m) -> m.region() == null ? "" : m.region()).thenComparing(Market::name));
        }
        return page(summary("fair-markets", s, where), list, page, m -> market(m, where, today));
    }

    private static StoreLite store(Store s, double fromLat, double fromLng) {
        return new StoreLite(s.id(), s.name(), s.category(), Math.round(distance(fromLat, fromLng, s.lat(), s.lng())));
    }

    private static MarketLite market(Market m, Where where, LocalDate today) {
        return new MarketLite(m.id(), m.name(), m.region(), m.road(), m.days(), m.permanent(),
                dist(where, m.lat(), m.lng()), isFairDay(m.days(), today));
    }

    private static Long dist(Where where, double lat, double lng) {
        return where.has() ? Math.round(distance(where.lat(), where.lng(), lat, lng)) : null;
    }

    private static <S, T> ThemePage<T> page(ThemeSummary theme, List<S> list, int page, Function<S, T> map) {
        int p = Math.max(page, 0);
        int from = Math.min(p * PAGE_SIZE, list.size());
        int to = Math.min(from + PAGE_SIZE, list.size());
        return new ThemePage<>(theme, list.subList(from, to).stream().map(map).toList(), p, to < list.size(), list.size());
    }
}
