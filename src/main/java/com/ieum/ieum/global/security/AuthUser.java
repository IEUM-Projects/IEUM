package com.ieum.ieum.global.security;

import com.ieum.ieum.user.UserRole;

/**
 * 로그인한 회원 (토큰에서 꺼낸 정보).
 * 컨트롤러에서 @AuthenticationPrincipal AuthUser authUser 로 받아 쓴다.
 */
public record AuthUser(Long id, UserRole role) {
}
