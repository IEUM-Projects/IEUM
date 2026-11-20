package com.ieum.ieum.auth;

import com.ieum.ieum.user.User;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 회원가입·로그인 요청과 응답 모양 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public static final String NICKNAME_REGEX = "^[가-힣a-zA-Z0-9]{2,12}$";
    public static final String NICKNAME_MESSAGE = "닉네임은 한글, 영문, 숫자로 2~12자예요.";

    /** 비밀번호 상세 규칙은 PasswordPolicy가 검사한다 (이메일·닉네임과 비교해야 해서 여기서는 빈칸만 확인) */
    public record SignupRequest(
            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "이메일 형식이 올바르지 않아요.")
            @Size(max = 100, message = "이메일이 너무 길어요.")
            String email,

            @NotBlank(message = "비밀번호를 입력해 주세요.")
            String password,

            @NotBlank(message = "닉네임을 입력해 주세요.")
            @Pattern(regexp = NICKNAME_REGEX, message = NICKNAME_MESSAGE)
            String nickname,

            // POL-01: 세 가지 모두 동의해야 가입할 수 있다
            @NotNull(message = "이용약관에 동의해 주세요.") @AssertTrue(message = "이용약관에 동의해 주세요.")
            Boolean agreeTerms,

            @NotNull(message = "개인정보 수집·이용에 동의해 주세요.") @AssertTrue(message = "개인정보 수집·이용에 동의해 주세요.")
            Boolean agreePrivacy,

            @NotNull(message = "만 14세 이상만 가입할 수 있어요.") @AssertTrue(message = "만 14세 이상만 가입할 수 있어요.")
            Boolean over14
    ) {
        /** 형식 검사 전에 앞뒤 공백 제거 (복사·붙여넣기로 공백이 섞이는 경우가 많음). 비밀번호는 그대로 둔다 */
        public SignupRequest {
            email = email == null ? null : email.trim();
            nickname = nickname == null ? null : nickname.trim();
        }
    }

    public record LoginRequest(
            @NotBlank(message = "이메일을 입력해 주세요.") String email,
            @NotBlank(message = "비밀번호를 입력해 주세요.") String password
    ) {
        public LoginRequest {
            email = email == null ? null : email.trim();
        }
    }

    /** 이메일 인증 (AUTH-02) */
    public record VerificationRequest(
            @NotBlank(message = "이메일을 입력해 주세요.")
            @Email(message = "이메일 형식이 올바르지 않아요.")
            @Size(max = 100, message = "이메일이 너무 길어요.")
            String email
    ) {
        public VerificationRequest {
            email = email == null ? null : email.trim();
        }
    }

    public record VerificationConfirmRequest(
            @NotBlank(message = "이메일을 입력해 주세요.") String email,
            @NotBlank(message = "인증번호를 입력해 주세요.") String code
    ) {
        public VerificationConfirmRequest {
            email = email == null ? null : email.trim();
        }
    }

    /** expiresIn: 번호를 쓸 수 있는 초, resendAfter: 다시 보낼 수 있을 때까지 초 */
    public record VerificationSent(String email, int expiresIn, int resendAfter) {
    }

    /** validFor: 이 시간(초) 안에 가입해야 함 */
    public record VerificationConfirmed(String email, int validFor) {
    }

    /** 가입 화면이 알아야 할 설정 */
    public record SignupOptions(boolean emailVerification) {
    }

    /** 닉네임 중복 확인 (AUTH-01). 쓸 수 없어도 오류(4xx)가 아니라 available=false 로 알려 준다 */
    public record NicknameCheck(String nickname, boolean available, String message) {
    }

    /** 로그인·재발급 응답. Refresh Token은 본문이 아니라 HttpOnly 쿠키로 따로 내려간다 */
    public record TokenResponse(String accessToken, long expiresIn, UserSummary user) {
    }

    /** needsAgreement: 지금 판의 이용약관·개인정보 처리방침에 아직 동의하지 않음 → 화면이 동의 화면으로 보낸다 (POL-01) */
    public record UserSummary(Long id, String nickname, String role, boolean needsAgreement) {
        public static UserSummary from(User user, int policyVersion) {
            return new UserSummary(user.getId(), user.getNickname(), user.getRole().name(), user.needsAgreement(policyVersion));
        }
    }

    /** 동의 화면 (카카오로 처음 가입했거나, 약관이 바뀐 뒤 처음 로그인한 회원) */
    public record AgreementRequest(
            @NotNull(message = "이용약관에 동의해 주세요.") @AssertTrue(message = "이용약관에 동의해 주세요.")
            Boolean agreeTerms,

            @NotNull(message = "개인정보 수집·이용에 동의해 주세요.") @AssertTrue(message = "개인정보 수집·이용에 동의해 주세요.")
            Boolean agreePrivacy,

            @NotNull(message = "만 14세 이상만 이용할 수 있어요.") @AssertTrue(message = "만 14세 이상만 이용할 수 있어요.")
            Boolean over14
    ) {
    }

    /** 서비스 → 컨트롤러로 넘기는 값: 응답 본문 + 쿠키에 넣을 Refresh Token 원본 */
    public record LoginResult(TokenResponse body, String refreshToken) {
    }
}
