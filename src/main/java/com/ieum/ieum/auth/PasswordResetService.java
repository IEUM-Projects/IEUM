package com.ieum.ieum.auth;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.mail.MailService;
import com.ieum.ieum.user.AuthProvider;
import com.ieum.ieum.user.User;
import com.ieum.ieum.user.UserRepository;
import com.ieum.ieum.user.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 비밀번호 찾기 (AUTH-03): 이메일로 받은 6자리 번호를 맞히면 새 비밀번호를 정할 수 있다.
 *
 *   1. 번호 받기   : 가입한 이메일이면 메일을 보낸다. 가입하지 않은 이메일이어도 똑같이 '보냈어요'라고 답한다
 *                    (이 화면으로 가입 여부를 알아내지 못하게). 보내기 제한은 가입 인증과 같다 (1분 1번·하루 5번).
 *   2. 번호 확인   : 10분 안에, 5번까지. 맞으면 10분 안에 새 비밀번호를 정해야 한다.
 *   3. 새 비밀번호 : 가입과 같은 규칙 + 지금 비밀번호와 달라야 함. 맞힌 번호는 한 번만 쓸 수 있다.
 *                    바꾸면 로그인 잠금을 풀고, 모든 기기의 로그인(Refresh Token)을 끊고, 바뀌었다는 메일을 보낸다.
 *
 * 카카오로 가입한 회원은 이메일·비밀번호가 없어서 대상이 아니다 (카카오로 로그인).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    static final String PURPOSE = "PASSWORD_RESET";
    /** 번호를 맞힌 뒤 새 비밀번호를 정해야 하는 시간 */
    public static final int RESET_MINUTES = 10;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm");

    private final EmailVerificationService verification;
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final MailService mailService;

    /** 1. 번호 받기 */
    @Transactional
    public AuthDtos.VerificationSent send(String rawEmail, String ip) {
        String email = AuthService.normalizeEmail(rawEmail);
        boolean member = findMember(email).isPresent();
        return verification.issue(email, PURPOSE, ip,
                member ? code -> new EmailVerificationService.Mail("[이음] 비밀번호 재설정 인증번호 " + code, mailText(code)) : null);
    }

    /** 2. 번호 확인 */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthDtos.VerificationConfirmed confirm(String rawEmail, String code) {
        return verification.check(AuthService.normalizeEmail(rawEmail), PURPOSE, code, RESET_MINUTES);
    }

    /** 3. 새 비밀번호 */
    @Transactional
    public void complete(String rawEmail, String newPassword) {
        String email = AuthService.normalizeEmail(rawEmail);
        LocalDateTime now = LocalDateTime.now();
        // 번호를 맞힌 회원만 여기까지 온다. 회원이 없으면 번호를 맞힐 수도 없었으므로 같은 오류로 끝낸다
        User user = findMember(email).orElseThrow(() -> new BusinessException(ErrorCode.RESET_NOT_VERIFIED));

        // 규칙부터 본다: 규칙에 안 맞아서 다시 입력할 때 맞힌 번호가 날아가지 않게
        List<String> errors = passwordPolicy.check(newPassword, email, user.getNickname());
        if (!errors.isEmpty()) throw new BusinessException(ErrorCode.WEAK_PASSWORD, errors);
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException(ErrorCode.WEAK_PASSWORD, List.of("지금 쓰는 비밀번호와 다른 비밀번호로 정해 주세요."));
        }
        if (!verification.consume(email, PURPOSE, RESET_MINUTES, now)) {
            throw new BusinessException(ErrorCode.RESET_NOT_VERIFIED);
        }

        user.resetPassword(passwordEncoder.encode(newPassword), now);
        int loggedOut = refreshTokenRepository.deleteByUserId(user.getId());   // 다른 기기(혹시 훔친 사람)의 로그인도 끊는다
        log.info("비밀번호를 재설정했어요: 회원 {} (끊은 로그인 {}개)", user.getId(), loggedOut);

        // 본인이 바꾼 게 아니면 알아챌 수 있게. 못 보내도 비밀번호 변경은 그대로 둔다
        try {
            mailService.send(email, CHANGED_SUBJECT, changedText(now, false));
        } catch (MailService.MailException e) {
            log.warn("비밀번호 변경 알림 메일을 보내지 못했어요: 회원 {}", user.getId());
        }
    }

    /** 비밀번호를 쓰는 이메일 회원 (탈퇴·카카오 회원 제외) */
    private Optional<User> findMember(String email) {
        return userRepository.findByEmail(email)
                .filter(u -> u.getStatus() != UserStatus.WITHDRAWN)
                .filter(u -> u.getProvider() == AuthProvider.LOCAL && u.getPassword() != null);
    }

    private static String mailText(String code) {
        return """
                안녕하세요, 이음입니다.

                비밀번호 찾기 화면에 아래 인증번호 6자리를 입력해 주세요.

                    %s

                - 인증번호는 %d분 동안만 쓸 수 있어요.
                - 비밀번호 찾기를 하지 않았다면 이 메일은 지워 주세요. 번호를 모르면 아무도 비밀번호를 바꿀 수 없어요.
                - 이음은 메일이나 전화로 인증번호를 묻지 않아요. 누구에게도 알려 주지 마세요.

                이음 — 우리 동네의 오래된 이야기를 잇습니다
                """.formatted(code, EmailVerificationService.CODE_MINUTES);
    }

    static final String CHANGED_SUBJECT = "[이음] 비밀번호가 바뀌었어요";

    /** 비밀번호가 바뀌었다는 알림 (비밀번호 찾기·내 정보에서 바꾸기가 함께 씀) */
    static String changedText(LocalDateTime when, boolean keptThisDevice) {
        return """
                안녕하세요, 이음입니다.

                %s에 이음 계정의 비밀번호가 바뀌었어요.
                %s

                직접 바꾼 게 아니라면 바로 '비밀번호 찾기'로 비밀번호를 다시 정하고, 개인정보 처리방침에 있는 운영자 연락처로 알려 주세요.

                이음 — 우리 동네의 오래된 이야기를 잇습니다
                """.formatted(when.format(WHEN), keptThisDevice
                ? "보안을 위해 바꾼 기기를 뺀 다른 기기에서 로그아웃했어요."
                : "보안을 위해 모든 기기에서 로그아웃했어요. 새 비밀번호로 다시 로그인해 주세요.");
    }
}
