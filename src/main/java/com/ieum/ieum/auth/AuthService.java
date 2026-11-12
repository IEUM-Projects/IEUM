package com.ieum.ieum.auth;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.security.JwtProvider;
import com.ieum.ieum.user.AuthProvider;
import com.ieum.ieum.user.NicknameGenerator;
import com.ieum.ieum.user.User;
import com.ieum.ieum.user.UserRepository;
import com.ieum.ieum.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 회원가입, 로그인, 토큰 재발급, 로그아웃.
 *
 * Refresh Token은 JWT가 아니라 아무 의미 없는 무작위 문자열이다.
 * DB에 해시를 저장해 두고, 재발급할 때마다 새 토큰으로 바꾼다(회전).
 * → 로그아웃하면 즉시 무효가 되고, 훔친 토큰이 한 번 쓰이면 원래 주인의 토큰이 무효가 되어 알아챌 수 있다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtProvider jwtProvider;
    private final NicknameGenerator nicknameGenerator;

    @Transactional
    public AuthDtos.UserSummary signup(AuthDtos.SignupRequest request) {
        String email = normalizeEmail(request.email());
        String nickname = request.nickname().trim();

        List<String> passwordErrors = passwordPolicy.check(request.password(), email, nickname);
        if (!passwordErrors.isEmpty()) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD, passwordErrors);
        }
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }

        User user = User.createLocal(email, passwordEncoder.encode(request.password()), nickname);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 거의 동시에 같은 이메일로 두 번 가입한 경우 (위 검사를 둘 다 통과한 경우)
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        return AuthDtos.UserSummary.from(user);
    }

    /**
     * 로그인. 실패해도 예외로 끝내면 안 되는 이유:
     * 실패 횟수를 저장해야 하므로 noRollbackFor로 "예외가 나도 변경 내용은 저장"하게 한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthDtos.LoginResult login(AuthDtos.LoginRequest request) {
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED)); // 이메일이 없는지 알려주지 않음

        if (user.getStatus() == UserStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (user.isLocked(now)) {
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (user.getPassword() == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            user.loginFailed(now);
            throw new BusinessException(user.isLocked(now) ? ErrorCode.ACCOUNT_LOCKED : ErrorCode.LOGIN_FAILED);
        }

        user.loginSucceeded(now);
        refreshTokenRepository.deleteExpiredByUserId(user.getId(), now);
        return issueTokens(user, now);
    }

    /**
     * 카카오 등 소셜 로그인.
     * 그 서비스의 회원 번호(providerId)로 우리 회원을 찾고, 처음이면 자동으로 가입시킨다.
     * 이메일 로그인과 똑같이 토큰을 발급하므로 이후 과정(재발급, 로그아웃, 내 정보)은 모두 같다.
     */
    @Transactional
    public AuthDtos.LoginResult loginWithSocial(AuthProvider provider, String providerId, String rawNickname) {
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.findByProviderAndProviderId(provider, providerId)
                .orElseGet(() -> userRepository.save(
                        User.createSocial(provider, providerId, nicknameGenerator.generate(rawNickname))));
        if (user.getStatus() == UserStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.ACCOUNT_WITHDRAWN);
        }
        user.loginSucceeded(now);
        refreshTokenRepository.deleteExpiredByUserId(user.getId(), now);
        return issueTokens(user, now);
    }

    /** 쿠키의 Refresh Token으로 새 Access Token + 새 Refresh Token 발급 */
    @Transactional
    public AuthDtos.LoginResult refresh(String refreshToken) {
        LocalDateTime now = LocalDateTime.now();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        RefreshToken saved = refreshTokenRepository.findByTokenHash(sha256(refreshToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        refreshTokenRepository.delete(saved); // 한 번 쓴 토큰은 바로 폐기 (회전)
        if (saved.isExpired(now)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        User user = userRepository.findById(saved.getUserId())
                .filter(u -> u.getStatus() != UserStatus.WITHDRAWN)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID));
        return issueTokens(user, now);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        refreshTokenRepository.findByTokenHash(sha256(refreshToken)).ifPresent(refreshTokenRepository::delete);
    }

    private AuthDtos.LoginResult issueTokens(User user, LocalDateTime now) {
        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole());

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokenRepository.save(new RefreshToken(user.getId(), sha256(refreshToken), now.plus(REFRESH_TOKEN_TTL)));

        var body = new AuthDtos.TokenResponse(accessToken, JwtProvider.ACCESS_TOKEN_TTL.toSeconds(),
                AuthDtos.UserSummary.from(user));
        return new AuthDtos.LoginResult(body, refreshToken);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
