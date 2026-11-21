package com.ieum.ieum.user;

import com.ieum.ieum.auth.AuthDtos;
import com.ieum.ieum.auth.RefreshTokenCookie;
import com.ieum.ieum.auth.RefreshTokenRepository;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.policy.Policy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET    /api/users/me              내 정보 (로그인 필요)
 * PATCH  /api/users/me              닉네임 변경 (로그인 필요)
 * POST   /api/users/me/agreements   이용약관·개인정보 수집·이용·만 14세 이상 동의 (POL-01)
 * DELETE /api/users/me              회원 탈퇴 (POL-01) {"password": "…"} 또는 카카오 회원은 {"confirm": "탈퇴"}
 *
 * @AuthenticationPrincipal: JwtAuthenticationFilter가 토큰에서 꺼내 둔 로그인 회원 정보를 받는다.
 */
@Slf4j
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {

    static final String WITHDRAW_WORD = "탈퇴";

    private final UserRepository userRepository;
    private final NicknamePolicy nicknamePolicy;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenCookie refreshTokenCookie;
    private final PasswordEncoder passwordEncoder;
    private final Policy policy;
    private final com.ieum.ieum.notification.NotificationService notificationService;
    private final com.ieum.ieum.favorite.FavoriteService favoriteService;
    private final com.ieum.ieum.auth.oauth.KakaoUnlinkService kakaoUnlink;

    public record MyInfo(Long id, String email, String nickname, String role, String provider,
                         String homeSigunguCode, LocalDateTime createdAt, boolean needsAgreement, LocalDateTime agreedAt) {
        static MyInfo from(User u, int policyVersion) {
            return new MyInfo(u.getId(), u.getEmail(), u.getNickname(), u.getRole().name(),
                    u.getProvider().name(), u.getHomeSigunguCode(), u.getCreatedAt(),
                    u.needsAgreement(policyVersion), u.getAgreedAt());
        }
    }

    public record UpdateRequest(
            @NotBlank(message = "닉네임을 입력해 주세요.")
            @Pattern(regexp = AuthDtos.NICKNAME_REGEX, message = AuthDtos.NICKNAME_MESSAGE)
            String nickname
    ) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public MyInfo me(@AuthenticationPrincipal AuthUser authUser) {
        return MyInfo.from(findActiveUser(authUser), policy.version());
    }

    @PatchMapping
    @Transactional
    public MyInfo update(@AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody UpdateRequest request) {
        User user = findActiveUser(authUser);
        String nickname = request.nickname().trim();
        String problem = nicknamePolicy.problem(nickname);
        if (problem != null && !nickname.equals(user.getNickname())) {
            throw new BusinessException(ErrorCode.NICKNAME_NOT_ALLOWED, List.of(problem));
        }
        if (!nickname.equals(user.getNickname()) && userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
        user.changeNickname(nickname);
        return MyInfo.from(user, policy.version());
    }

    @PostMapping("/agreements")
    @Transactional
    public MyInfo agree(@AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody AuthDtos.AgreementRequest request) {
        User user = findActiveUser(authUser);
        user.agree(policy.version(), LocalDateTime.now());
        return MyInfo.from(user, policy.version());
    }

    public record WithdrawRequest(String password, String confirm) {
    }

    /**
     * 회원 탈퇴: 개인정보(이메일·비밀번호·카카오 회원 번호·관심 지역·찜한 곳·알림)를 바로 지우고, 로그인을 모두 끊는다.
     * 이메일 회원은 비밀번호로, 카카오 회원은 '탈퇴'를 직접 입력해서 한 번 더 확인한다.
     * 관리자는 실수로 관리자가 없어지지 않게 탈퇴할 수 없다 (먼저 DB에서 권한을 USER로 바꿈).
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal AuthUser authUser,
                                         @RequestBody(required = false) WithdrawRequest request) {
        User user = findActiveUser(authUser);
        if (user.getRole() == UserRole.ADMIN) throw new BusinessException(ErrorCode.ADMIN_CANNOT_WITHDRAW);
        if (user.getProvider() == AuthProvider.LOCAL) {
            String password = request == null ? null : request.password();
            if (password == null || password.isEmpty() || user.getPassword() == null
                    || !passwordEncoder.matches(password, user.getPassword())) {
                throw new BusinessException(ErrorCode.WITHDRAW_PASSWORD_MISMATCH);
            }
        } else {
            String confirm = request == null || request.confirm() == null ? "" : request.confirm().strip();
            if (!WITHDRAW_WORD.equals(confirm)) throw new BusinessException(ErrorCode.WITHDRAW_CONFIRM_REQUIRED);
        }
        Long id = user.getId();
        // 카카오 회원이면 커밋 뒤 카카오 연결 끊기 (KAKAO-UNLINK). 번호는 withdraw() 가 지우기 전에 꺼낸다
        if (user.getProvider() == AuthProvider.KAKAO) kakaoUnlink.unlinkAfterCommit(user.getProviderId(), id);
        user.withdraw();
        userRepository.flush();
        refreshTokenRepository.deleteByUserId(id);
        favoriteService.deleteAll(id);   // 찜한 곳도 개인정보라 바로 지운다 (FAV-01)
        notificationService.deleteAll(id);   // 알림도 (NOTI-01)
        log.info("회원 탈퇴: {}", id);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, refreshTokenCookie.clear()).build();
    }

    private User findActiveUser(AuthUser authUser) {
        return userRepository.findById(authUser.id())
                .filter(u -> u.getStatus() != UserStatus.WITHDRAWN)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
