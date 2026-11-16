package com.ieum.ieum.global.error;

import java.util.List;

/**
 * 모든 오류 응답의 모양.
 * {
 *   "code": "WEAK_PASSWORD",
 *   "message": "비밀번호 규칙을 확인해 주세요.",
 *   "details": ["10자 이상 입력해 주세요.", "같은 문자를 3번 이상 연속으로 쓸 수 없어요."]
 * }
 */
public record ErrorResponse(String code, String message, List<String> details) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), List.of());
    }

    public static ErrorResponse of(ErrorCode errorCode, List<String> details) {
        return new ErrorResponse(errorCode.name(), errorCode.message(), details);
    }

    /** 보안 필터처럼 Spring MVC 밖에서 직접 응답을 써야 할 때 쓰는 JSON 문자열 */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\"code\":").append(quote(code))
                .append(",\"message\":").append(quote(message)).append(",\"details\":[");
        for (int i = 0; i < details.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(details.get(i)));
        }
        return sb.append("]}").toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
