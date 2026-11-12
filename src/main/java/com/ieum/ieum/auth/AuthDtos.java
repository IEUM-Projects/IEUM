package com.ieum.ieum.auth;

import com.ieum.ieum.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
            String nickname
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

    /** 로그인·재발급 응답. Refresh Token은 본문이 아니라 HttpOnly 쿠키로 따로 내려간다 */
    public record TokenResponse(String accessToken, long expiresIn, UserSummary user) {
    }

    public record UserSummary(Long id, String nickname, String role) {
        public static UserSummary from(User user) {
            return new UserSummary(user.getId(), user.getNickname(), user.getRole().name());
        }
    }

    /** 서비스 → 컨트롤러로 넘기는 값: 응답 본문 + 쿠키에 넣을 Refresh Token 원본 */
    public record LoginResult(TokenResponse body, String refreshToken) {
    }
}
