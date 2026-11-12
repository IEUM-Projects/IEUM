package com.ieum.ieum.user;

import com.ieum.ieum.auth.AuthDtos;
import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * GET   /api/users/me   내 정보 (로그인 필요)
 * PATCH /api/users/me   닉네임 변경 (로그인 필요)
 *
 * @AuthenticationPrincipal: JwtAuthenticationFilter가 토큰에서 꺼내 둔 로그인 회원 정보를 받는다.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    public record MyInfo(Long id, String email, String nickname, String role, String provider,
                         String homeSigunguCode, LocalDateTime createdAt) {
        static MyInfo from(User u) {
            return new MyInfo(u.getId(), u.getEmail(), u.getNickname(), u.getRole().name(),
                    u.getProvider().name(), u.getHomeSigunguCode(), u.getCreatedAt());
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
        return MyInfo.from(findActiveUser(authUser));
    }

    @PatchMapping
    @Transactional
    public MyInfo update(@AuthenticationPrincipal AuthUser authUser, @Valid @RequestBody UpdateRequest request) {
        User user = findActiveUser(authUser);
        String nickname = request.nickname().trim();
        if (!nickname.equals(user.getNickname()) && userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
        }
        user.changeNickname(nickname);
        return MyInfo.from(user);
    }

    private User findActiveUser(AuthUser authUser) {
        return userRepository.findById(authUser.id())
                .filter(u -> u.getStatus() != UserStatus.WITHDRAWN)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }
}
