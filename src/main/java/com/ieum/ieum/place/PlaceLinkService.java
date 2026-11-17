package com.ieum.ieum.place;

import com.ieum.ieum.festival.KakaoLocalClient;
import com.ieum.ieum.festival.KakaoLocalClient.Place;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 가게 ↔ 카카오맵 장소 잇기 (DATA-05)
 *
 * 카카오 로컬 API 이용 조건: 저장할 수 있는 것은 장소 ID 뿐이다.
 * 그래서 카카오 검색 결과(이름·좌표)는 이 자리에서 비교만 하고 버리고, 맞는 장소의 ID만 stores.kakao_place_id 에 적는다.
 * (가게 화면의 '카카오맵' 버튼과, 가게 화면에서 그때그때 불러오는 전화번호에 쓴다)
 *
 * stores.enrich_status
 *   PENDING  아직 안 찾음 → 자동 연결: 이름이 같고 브이월드 좌표에서 300m 안이면 LINKED, 아니면 REVIEW
 *            이름은 '로쏘 주식회사(성심당)' → '로쏘', '성심당'처럼 회사 형태·괄호를 나눈 이름으로 차례로 찾는다
 *   LINKED   장소 ID 있음 (자동 또는 관리자)
 *   REVIEW   관리자가 확인해야 함 (후보가 없거나 애매함, 예전 카카오 매칭에서 '애매함'이던 곳)
 *   NO_PLACE 관리자가 '카카오맵에 없음'으로 정함
 * 좌표가 아직 없는 가게는 건너뛴다 (좌표를 찾은 뒤 다음 실행 때).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceLinkService {

    static final int AUTO_RADIUS_M = 300;
    static final int SEARCH_RADIUS_M = 2000;

    private final NamedParameterJdbcTemplate jdbc;
    private final KakaoLocalClient kakao;
    private volatile PlaceJob job;

    public Optional<PlaceJob.View> current() {
        PlaceJob j = job;
        return j == null ? Optional.empty() : Optional.of(j.view());
    }

    public boolean isRunning() {
        PlaceJob j = job;
        return j != null && "RUNNING".equals(j.state);
    }

    /**
     * 자동 연결을 따로 시작 (관리자 버튼). 아직 안 찾은 곳 + 자동 연결이 못 찾아 '확인 필요'에 둔 곳
     * (관리자가 손댄 곳, 장소 ID가 이미 있는 곳은 빼고). 이미 하고 있으면 빈 값
     */
    public synchronized Optional<PlaceJob.View> start() {
        if (isRunning()) return Optional.empty();
        PlaceJob j = new PlaceJob("LINK");
        job = j;
        Thread.ofVirtual().name("place-link").start(() -> runSafely(j, true));
        return Optional.of(j.view());
    }

    /** 좌표 찾기가 끝난 뒤 같은 스레드에서 이어서 */
    void autoLink() {
        PlaceJob j;
        synchronized (this) {
            if (isRunning()) return;
            j = new PlaceJob("LINK");
            job = j;
        }
        runSafely(j, false);
    }

    private void runSafely(PlaceJob j, boolean retryReview) {
        try {
            run(j, retryReview);
        } catch (RuntimeException e) {
            log.error("카카오맵 자동 연결 중 오류", e);
            j.finish("FAILED", "오류로 멈췄어요: " + e.getClass().getSimpleName());
        }
    }

    void run(PlaceJob j, boolean retryReview) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.id, s.name, s.latitude, s.longitude FROM stores s
                WHERE s.latitude IS NOT NULL AND (s.enrich_status = 'PENDING'
                   OR (:retry AND s.enrich_status = 'REVIEW' AND s.kakao_place_id IS NULL
                       AND NOT EXISTS (SELECT 1 FROM store_edit_logs l WHERE l.store_id = s.id AND l.field = 'KAKAO_PLACE')))
                ORDER BY s.id
                """, new MapSqlParameterSource("retry", retryReview));
        j.total = rows.size();
        if (rows.isEmpty()) {
            log.info("카카오맵 자동 연결: 새로 이을 가게가 없어요.");
            j.finish("DONE", "새로 이을 가게가 없어요.");
            return;
        }
        if (!kakao.hasKey()) {
            j.finish("FAILED", "카카오 REST API 키(kakao.rest-api-key)가 없어서 잇지 못했어요.");
            return;
        }
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String name = (String) r.get("name");
            double lat = ((Number) r.get("latitude")).doubleValue();
            double lng = ((Number) r.get("longitude")).doubleValue();
            Optional<String> placeId = Optional.empty();
            try {
                List<String> names = aliases(name);
                for (String alias : names) {
                    placeId = best(names, lat, lng, kakao.keyword(alias, 15, lat, lng, SEARCH_RADIUS_M));
                    if (placeId.isPresent()) break;
                }
            } catch (KakaoLocalClient.KakaoException e) {
                log.warn("카카오맵 자동 연결을 멈춤 (다음에 이어서): {}", e.getMessage());
                j.finish("FAILED", "카카오 장소 검색을 쓸 수 없어 멈췄어요. 남은 곳은 다음에 이어서 해요.");
                return;
            }
            j.done.incrementAndGet();
            if (placeId.isPresent()) {
                jdbc.update("UPDATE stores SET kakao_place_id = :pid, enrich_status = 'LINKED' WHERE id = :id AND enrich_status IN ('PENDING', 'REVIEW') AND kakao_place_id IS NULL",
                        new MapSqlParameterSource().addValue("pid", placeId.get()).addValue("id", id));
                j.found.incrementAndGet();
            } else {
                jdbc.update("UPDATE stores SET enrich_status = 'REVIEW' WHERE id = :id AND enrich_status = 'PENDING'",
                        new MapSqlParameterSource("id", id));
                j.failed.incrementAndGet();
            }
        }
        String msg = "자동으로 이은 곳 " + j.found.get() + "곳, 관리자 확인이 필요한 곳 " + j.failed.get() + "곳";
        log.info("카카오맵 자동 연결: {}", msg);
        j.finish("DONE", msg + ".");
    }

    /** 이름 중 하나와 같고(띄어쓰기·괄호·'본점'·회사 형태 무시) 300m 안인 곳 중 가장 가까운 곳 */
    static Optional<String> best(List<String> names, double lat, double lng, List<Place> places) {
        List<String> targets = names.stream().map(PlaceLinkService::norm).filter(t -> t.length() >= 2).toList();
        if (targets.isEmpty()) return Optional.empty();
        return places.stream()
                .filter(p -> p.id() != null && p.id().matches("\\d{1,20}"))
                .filter(p -> matchesAny(targets, p.name()))
                .filter(p -> distance(lat, lng, p.lat(), p.lng()) <= AUTO_RADIUS_M)
                .min(Comparator.comparingDouble(p -> distance(lat, lng, p.lat(), p.lng())))
                .map(Place::id);
    }

    static boolean matchesAny(List<String> normTargets, String placeName) {
        String n = norm(placeName);
        String inParen = placeName == null ? "" : placeName.replaceAll("^.*\\(([^)]*)\\).*$", "$1");
        String n2 = inParen.equals(placeName) ? "" : norm(inParen);
        return normTargets.stream().anyMatch(t -> sameName(t, n) || (!n2.isEmpty() && sameName(t, n2)));
    }

    private static final String CORP = "(주식회사|유한회사|농업회사법인|영농조합법인|사회적협동조합|협동조합|\\(주\\)|㈜|\\(유\\))";

    /**
     * 카카오에서 찾아볼 이름들 (겹치면 한 번만):
     * '로쏘 주식회사(성심당)' → [로쏘, 성심당], '한국문구도매 , 활문사' → [한국문구도매, 활문사], '오복떡집(오복떡본가)' → [오복떡집, 오복떡본가]
     */
    static List<String> aliases(String name) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (name == null) return List.of();
        String clean = name.replaceAll(CORP, " ");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\(([^)]*)\\)").matcher(clean);
        List<String> inner = new java.util.ArrayList<>();
        while (m.find()) inner.add(m.group(1));
        String outside = clean.replaceAll("\\([^)]*\\)", " ");
        for (String part : outside.split("[,/]")) out.add(part.replaceAll("\\s+", " ").strip());
        for (String part : inner) for (String p : part.split("[,/]")) out.add(p.replaceAll("\\s+", " ").strip());
        out.removeIf(s -> s.replace(" ", "").length() < 2);
        if (out.isEmpty()) out.add(name.strip());
        return new java.util.ArrayList<>(out).subList(0, Math.min(3, out.size()));
    }

    static boolean sameName(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        // '할매국수' ↔ '할매국수 본점', '원조할매국수' ↔ '할매국수'처럼 한쪽이 다른 쪽을 품고, 짧은 쪽이 3자 이상
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter == a ? b : a;
        return shorter.length() >= 3 && longer.contains(shorter) && longer.length() - shorter.length() <= 4;
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll(CORP, "").replaceAll("\\([^)]*\\)", "").replaceAll("\\s*(본점|본관|직영점)\\s*$", "")
                .replaceAll("[\\s·.,&'\"\\-_/]", "").toLowerCase();
    }

    public static double distance(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double h = Math.pow(Math.sin((p2 - p1) / 2), 2)
                + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(lng2 - lng1) / 2), 2);
        return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
