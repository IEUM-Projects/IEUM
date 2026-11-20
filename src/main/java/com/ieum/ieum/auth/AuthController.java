package com.ieum.ieum.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/auth/signup   회원가입              → 201 + 회원 요약
 * GET  /api/auth/nickname-check?nickname=   닉네임 중복 확인 → 200 {available, message} (AUTH-01)
 * GET  /api/auth/signup-options              가입 화면 설정 → {emailVerification} (AUTH-02)
 * POST /api/auth/email-verification          인증번호 메일 보내기 {email} → {expiresIn, resendAfter}
 * POST /api/auth/email-verification/confirm  인증번호 확인 {email, code} → {validFor}
 * POST /api/auth/login    로그인                → Access Token(본문) + Refresh Token(HttpOnly 쿠키)
 * POST /api/auth/refresh  Access Token 재발급   → 쿠키의 Refresh Token 사용, 새 쿠키로 교체
 * POST /api/auth/logout   로그아웃              → Refresh Token 삭제, 쿠키 제거
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookie refreshTokenCookie;
    private final EmailVerificationService emailVerificationService;

    @PostMapping("/signup")
    public ResponseEntity<AuthDtos.UserSummary> signup(@Valid @RequestBody AuthDtos.SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.signup(request));
    }

    @GetMapping("/signup-options")
    public AuthDtos.SignupOptions signupOptions() {
        return new AuthDtos.SignupOptions(emailVerificationService.required());
    }

    @PostMapping("/email-verification")
    public AuthDtos.VerificationSent sendVerification(@Valid @RequestBody AuthDtos.VerificationRequest request,
                                                      HttpServletRequest http) {
        return emailVerificationService.send(request.email(), http.getRemoteAddr());
    }

    @PostMapping("/email-verification/confirm")
    public AuthDtos.VerificationConfirmed confirmVerification(@Valid @RequestBody AuthDtos.VerificationConfirmRequest request) {
        return emailVerificationService.confirm(request.email(), request.code());
    }

    @GetMapping("/nickname-check")
    public AuthDtos.NicknameCheck nicknameCheck(@RequestParam(required = false) String nickname) {
        return authService.checkNickname(nickname);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return withCookie(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthDtos.TokenResponse> refresh(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        return withCookie(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshTokenCookie.NAME, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear())
                .build();
    }

    private ResponseEntity<AuthDtos.TokenResponse> withCookie(AuthDtos.LoginResult result) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookie.create(result.refreshToken(), AuthService.REFRESH_TOKEN_TTL))
                .body(result.body());
    }
}
