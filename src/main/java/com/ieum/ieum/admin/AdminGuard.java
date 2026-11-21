package com.ieum.ieum.admin;

import com.ieum.ieum.global.error.BusinessException;
import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.security.AuthUser;
import com.ieum.ieum.user.User;
import com.ieum.ieum.user.UserRepository;
import com.ieum.ieum.user.UserRole;
import com.ieum.ieum.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 관리자 확인.
 *
 * SecurityConfig가 토큰의 권한(ROLE_ADMIN)으로 /api/admin/** 을 한 번 막는다.
 * 그런데 토큰은 발급 후 30분 동안 유효해서, 그 사이 DB에서 관리자 권한을 빼도 토큰에는 남아 있다.
 * 그래서 관리자 API는 요청마다 DB의 회원 정보로 한 번 더 확인한다.
 */
@Component
@RequiredArgsConstructor
public class AdminGuard {

    private final UserRepository userRepository;

    public User requireAdmin(AuthUser authUser) {
        if (authUser == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        User user = userRepository.findById(authUser.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        if (user.getStatus() != UserStatus.ACTIVE || user.getRole() != UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return user;
    }
}
