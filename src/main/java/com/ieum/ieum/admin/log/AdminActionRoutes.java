package com.ieum.ieum.admin.log;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 관리자 API 주소 → 작업 기록 한 줄 (ADM-03).
 * 새 관리자 API 를 만들면 여기에 한 줄 더한다. 빠뜨려도 '기타 관리자 작업'으로는 남는다.
 *
 * 한 줄 설명(summary)은 요청 본문에서 사람이 읽을 칸만 골라 만든다. 비밀번호 같은 칸은 애초에 관리자 API 에 없지만,
 * 본문 전체를 저장하지 않고 정해진 칸만 꺼낸다.
 */
final class AdminActionRoutes {

    record Entry(String category, String action, String label, String targetType, String targetId, String summary) {
    }

    private record Route(String method, Pattern path, String category, String action, String label, String targetType,
                         BiFunction<JsonNode, JsonNode, String> summary) {
    }

    private static final List<Route> ROUTES = new ArrayList<>();

    static final Map<String, String> CATEGORY_LABELS = Map.of(
            "USER", "회원", "COMMUNITY", "동네 이야기", "STORE", "제보·가게", "MARKET", "시장", "NOTICE", "공지", "DATA", "자료 작업", "OTHER", "기타");

    private static void add(String method, String path, String category, String action, String label, String targetType,
                            BiFunction<JsonNode, JsonNode, String> summary) {
        // {id} 는 첫 번째 잡음 묶음이 대상 번호가 된다
        String regex = "^/api/admin" + path.replace("{id}", "([^/]+)").replace("{type}", "(post|comment|POST|COMMENT)") + "$";
        ROUTES.add(new Route(method, Pattern.compile(regex), category, action, label, targetType, summary));
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return null;
        String s = n.get(field).asString().strip();
        return s.isEmpty() ? null : s;
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) out.add(p);
        return out.isEmpty() ? null : String.join(" · ", out);
    }

    private static final Map<String, String> DECISIONS = Map.of("APPLIED", "반영", "REJECTED", "반려");
    private static final Map<String, String> RECOMMEND = Map.of("PENDING", "확인 전", "ADDED", "지도에 추가", "EXISTS", "이미 있는 곳", "HOLD", "보류");
    private static final Map<String, String> CLOSURE = Map.of("CLOSE", "폐업 처리", "DISMISS", "문제없음", "RESET", "처리 전으로 되돌림");
    private static final Map<String, String> LINK = Map.of("LINK", "연결", "IGNORE", "연결 안 함", "RESET", "처리 전으로 되돌림");

    static {
        // 제보·가게
        add("POST", "/stores/{id}/review", "STORE", "STORE_REPORT_REVIEW", "영업 정보 제보 처리", "STORE", (b, r) -> {
            String d = DECISIONS.getOrDefault(String.valueOf(text(b, "decision")), text(b, "decision"));
            int n = b != null && b.has("reportIds") ? b.get("reportIds").size() : 0;
            String changed = b != null && b.has("changes") ? fields(b.get("changes")) : null;
            return join(d, n > 0 ? "제보 " + n + "건" : null, changed, text(b, "note"));
        });
        add("POST", "/stores/{id}/edit", "STORE", "STORE_EDIT", "가게 정보 고침", "STORE", (b, r) -> fields(b));
        add("POST", "/closures/{id}", "STORE", "CLOSURE_REVIEW", "폐업 의심 처리", "STORE",
                (b, r) -> CLOSURE.getOrDefault(String.valueOf(text(b, "action")), text(b, "action")));
        add("POST", "/closures/import", "DATA", "LICENSE_IMPORT", "인허가 파일 올리기", null, (b, r) -> null);
        add("PUT", "/places/{id}/place", "STORE", "PLACE_LINK", "카카오맵 장소 연결", "STORE", (b, r) -> null);
        add("DELETE", "/places/{id}/place", "STORE", "PLACE_UNLINK", "카카오맵 장소 연결 풀기", "STORE", (b, r) -> null);
        add("POST", "/places/{id}/no-place", "STORE", "PLACE_NONE", "카카오맵에 없는 곳으로 표시", "STORE", (b, r) -> null);
        add("PUT", "/places/{id}/category", "STORE", "STORE_CATEGORY", "가게 분류 고침", "STORE", (b, r) -> text(b, "category"));
        add("PUT", "/places/{id}/address", "STORE", "STORE_ADDRESS", "가게 주소 고침", "STORE", (b, r) -> text(b, "roadAddress"));
        add("POST", "/places/jobs/geocode", "DATA", "JOB_GEOCODE", "좌표 채우기 실행", null, (b, r) -> null);
        add("POST", "/places/jobs/link", "DATA", "JOB_PLACE_LINK", "카카오맵 장소 자동 연결 실행", null, (b, r) -> null);
        // 시장
        add("POST", "/market-links/{id}", "MARKET", "MARKET_LINK", "시장 연결 확인", "FACILITY",
                (b, r) -> join(LINK.getOrDefault(String.valueOf(text(b, "action")), text(b, "action")),
                        text(b, "marketId") == null ? null : "시장 #" + text(b, "marketId")));
        add("PUT", "/stories/{id}", "MARKET", "STORY_SAVE", "시장 이야기 저장", "MARKET", (b, r) -> null);
        add("POST", "/stories/{id}/publish", "MARKET", "STORY_PUBLISH", "시장 이야기 공개", "MARKET", (b, r) -> null);
        add("POST", "/stories/{id}/hide", "MARKET", "STORY_HIDE", "시장 이야기 숨김", "MARKET", (b, r) -> null);
        // 공지
        add("POST", "/notices", "NOTICE", "NOTICE_CREATE", "공지 작성", "NOTICE", (b, r) -> join(text(b, "title"), flags(b)));
        add("PUT", "/notices/{id}", "NOTICE", "NOTICE_UPDATE", "공지 고침", "NOTICE", (b, r) -> join(text(b, "title"), flags(b)));
        add("DELETE", "/notices/{id}", "NOTICE", "NOTICE_DELETE", "공지 지움", "NOTICE", (b, r) -> null);
        // 동네 이야기
        add("POST", "/community/{type}/{id}/hide", "COMMUNITY", "HIDE", "가림", null,
                (b, r) -> join(Boolean.TRUE.equals(b != null && b.has("temp") && b.get("temp").asBoolean()) ? "임시 숨김 30일" : null, text(b, "reason")));
        add("POST", "/community/{type}/{id}/restore", "COMMUNITY", "RESTORE", "다시 보이게 / 신고 문제없음", null, (b, r) -> null);
        add("PUT", "/community/posts/{id}/recommend", "COMMUNITY", "RECOMMEND", "가게 추천 처리", "POST",
                (b, r) -> join(RECOMMEND.getOrDefault(String.valueOf(text(b, "status")), text(b, "status")), text(b, "note")));
        // 회원
        add("POST", "/users/{id}/suspend", "USER", "USER_SUSPEND", "작성 정지", "USER", (b, r) -> null);
        add("POST", "/users/{id}/withdraw", "USER", "USER_WITHDRAW", "강제 탈퇴", "USER", (b, r) -> text(b, "reason"));
        add("DELETE", "/users/{id}/ban", "USER", "USER_REJOIN", "재가입 허용", "USER", (b, r) -> null);
    }

    private static String fields(JsonNode b) {
        if (b == null || !b.isObject()) return null;
        Map<String, String> names = Map.of("infoStatus", "영업 정보 상태", "hours", "영업시간", "phone", "전화번호",
                "roadAddress", "도로명 주소");
        List<String> out = new ArrayList<>();
        for (String f : b.propertyNames()) {
            if (b.get(f) != null && !b.get(f).isNull()) out.add(names.getOrDefault(f, f));
        }
        return out.isEmpty() ? null : String.join(", ", out) + " 고침";
    }

    private static String flags(JsonNode b) {
        if (b == null) return null;
        return join(b.has("pinned") && b.get("pinned").asBoolean() ? "상단 고정" : null,
                b.has("visible") && !b.get("visible").isNull() && !b.get("visible").asBoolean() ? "숨김" : null);
    }

    /** 맞는 길이 없으면 기타. body/response 는 JSON 이 아니면 null */
    static Entry resolve(String method, String path, JsonNode body, JsonNode response) {
        for (Route r : ROUTES) {
            if (!r.method().equals(method)) continue;
            Matcher m = r.path().matcher(path);
            if (!m.matches()) continue;
            String targetType = r.targetType();
            String targetId = null;
            String action = r.action();
            String label = r.label();
            if (path.startsWith("/api/admin/community/") && m.groupCount() >= 2) {
                // /community/{type}/{id}/... : 글인지 댓글인지
                boolean post = m.group(1).equalsIgnoreCase("post");
                targetType = post ? "POST" : "COMMENT";
                targetId = m.group(2);
                action = (post ? "POST_" : "COMMENT_") + r.action();
                label = (post ? "글 " : "댓글 ") + r.label();
            } else if (m.groupCount() >= 1) {
                targetId = m.group(1);
            }
            if (r.action().equals("USER_SUSPEND") && body != null && body.has("suspend") && !body.get("suspend").asBoolean()) {
                action = "USER_UNSUSPEND";
                label = "작성 정지 풀기";
            }
            if (targetId == null && "NOTICE".equals(targetType) && response != null && response.has("id")) {
                targetId = response.get("id").asString();   // 새 공지 번호는 응답에서
            }
            String summary = null;
            try {
                summary = r.summary().apply(body, response);
            } catch (RuntimeException ignored) {
                // 본문 모양이 예상과 달라도 기록은 남긴다
            }
            return new Entry(r.category(), action, label, targetType, targetId, summary);
        }
        return new Entry("OTHER", "OTHER", "기타 관리자 작업", null, null, method + " " + path);
    }

    /** 화면에 보여 줄 작업 이름 (기록에는 코드만 있으므로) */
    static String label(String action) {
        return switch (action) {
            case "STORE_REPORT_REVIEW" -> "영업 정보 제보 처리";
            case "STORE_EDIT" -> "가게 정보 고침";
            case "CLOSURE_REVIEW" -> "폐업 의심 처리";
            case "LICENSE_IMPORT" -> "인허가 파일 올리기";
            case "PLACE_LINK" -> "카카오맵 장소 연결";
            case "PLACE_UNLINK" -> "카카오맵 장소 연결 풀기";
            case "PLACE_NONE" -> "카카오맵에 없는 곳으로 표시";
            case "STORE_CATEGORY" -> "가게 분류 고침";
            case "STORE_ADDRESS" -> "가게 주소 고침";
            case "JOB_GEOCODE" -> "좌표 채우기 실행";
            case "JOB_PLACE_LINK" -> "카카오맵 장소 자동 연결 실행";
            case "MARKET_LINK" -> "시장 연결 확인";
            case "STORY_SAVE" -> "시장 이야기 저장";
            case "STORY_PUBLISH" -> "시장 이야기 공개";
            case "STORY_HIDE" -> "시장 이야기 숨김";
            case "NOTICE_CREATE" -> "공지 작성";
            case "NOTICE_UPDATE" -> "공지 고침";
            case "NOTICE_DELETE" -> "공지 지움";
            case "POST_HIDE" -> "글 가림";
            case "COMMENT_HIDE" -> "댓글 가림";
            case "POST_RESTORE" -> "글 다시 보이게 / 신고 문제없음";
            case "COMMENT_RESTORE" -> "댓글 다시 보이게 / 신고 문제없음";
            case "RECOMMEND" -> "가게 추천 처리";
            case "USER_SUSPEND" -> "작성 정지";
            case "USER_UNSUSPEND" -> "작성 정지 풀기";
            case "USER_WITHDRAW" -> "강제 탈퇴";
            case "USER_REJOIN" -> "재가입 허용";
            default -> "기타 관리자 작업";
        };
    }

    private AdminActionRoutes() {
    }
}
