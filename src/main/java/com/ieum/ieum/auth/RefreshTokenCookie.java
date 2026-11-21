package com.ieum.ieum.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Refresh Token을 담는 쿠키.
 *
 * - HttpOnly : 자바스크립트가 읽을 수 없음 → 화면에 악성 스크립트가 끼어들어도 토큰을 훔칠 수 없음
 * - Secure   : HTTPS에서만 전송 (localhost는 브라우저가 예외로 허용)
 * - SameSite=Strict : 다른 사이트에서 시작된 요청에는 붙지 않음 (CSRF 방지)
 * - Path=/api/auth  : 로그인 관련 주소로만 전송 → 다른 API 요청에는 아예 실리지 않음
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "ieum_refresh";
    public static final String PATH = "/api/auth";

    private final boolean secure;

    public RefreshTokenCookie(@Value("${auth.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public String create(String token, Duration maxAge) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(maxAge)
                .build()
                .toString();
    }

    /** 로그아웃: 같은 이름·경로로 유효기간 0인 쿠키를 보내면 브라우저가 지운다 */
    public String clear() {
        return create("", Duration.ZERO);
    }
}
