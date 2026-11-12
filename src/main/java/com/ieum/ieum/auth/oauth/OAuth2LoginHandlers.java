package com.ieum.ieum.auth.oauth;

import com.ieum.ieum.auth.AuthDtos;
import com.ieum.ieum.auth.AuthService;
import com.ieum.ieum.auth.RefreshTokenCookie;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.user.AuthProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 카카오 로그인이 끝났을 때 실행되는 처리.
 *
 * 성공: 우리 회원을 찾거나 만들고 → Refresh Token 쿠키를 심고 → 지도 화면(/?login=success)으로 보낸다.
 *       화면은 /api/auth/refresh를 불러서 Access Token을 받는다. (Access Token을 주소에 붙이지 않기 위해)
 * 실패: 지도 화면(/?login=failed&reason=...)으로 보내서 화면이 안내 문구를 띄우게 한다.
 *
 * 어느 쪽이든 로그인 과정에 잠깐 쓴 세션은 바로 지운다. 이후 요청은 토큰으로만 확인한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2LoginHandlers {

    public static final String SUCCESS_URL = "/?login=success";
    public static final String FAILURE_URL = "/?login=failed&reason=";

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;

    public AuthenticationSuccessHandler successHandler() {
        return this::onSuccess;
    }

    public AuthenticationFailureHandler failureHandler() {
        return this::onFailure;
    }

    private void onSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
            AuthProvider provider = AuthProvider.valueOf(token.getAuthorizedClientRegistrationId().toUpperCase());
            KakaoUserInfo info = KakaoUserInfo.from(token.getPrincipal().getAttributes());

            AuthDtos.LoginResult result = authService.loginWithSocial(provider, info.id(), info.nickname());
            response.addHeader(HttpHeaders.SET_COOKIE,
                    refreshTokenCookie.create(result.refreshToken(), AuthService.REFRESH_TOKEN_TTL));
            clearSession(request);
            response.sendRedirect(SUCCESS_URL);
        } catch (BusinessException e) {
            clearSession(request);
            response.sendRedirect(FAILURE_URL + e.errorCode().name());
        } catch (RuntimeException e) {
            log.error("카카오 로그인 처리 중 오류", e);
            clearSession(request);
            response.sendRedirect(FAILURE_URL + "ERROR");
        }
    }

    private void onFailure(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        // 사용자가 동의 화면에서 [취소]를 누르면 카카오가 error=access_denied로 돌려보낸다
        String reason = "ERROR";
        if (exception instanceof OAuth2AuthenticationException oauth) {
            String code = oauth.getError().getErrorCode();
            reason = "access_denied".equals(code) ? "CANCELLED" : "ERROR";
            if (!"access_denied".equals(code)) {
                log.warn("카카오 로그인 실패: {}", oauth.getError());
            }
        }
        clearSession(request);
        response.sendRedirect(FAILURE_URL + reason);
    }

    private static void clearSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
