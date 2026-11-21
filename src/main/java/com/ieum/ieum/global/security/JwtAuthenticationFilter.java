package com.ieum.ieum.global.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 요청마다 한 번 실행되는 필터.
 * Authorization: Bearer {토큰} 헤더가 있으면 토큰을 검사해서 "로그인한 상태"로 만든다.
 *
 * 토큰이 없거나 잘못돼도 여기서는 요청을 막지 않는다.
 * 로그인이 필요한 주소인지는 SecurityConfig가 판단하고, 그때 JsonAuthenticationEntryPoint가 401을 응답한다.
 * (만료·위조 여부는 request에 표시해 두어서 401 응답에 이유를 담을 수 있게 한다)
 */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_ERROR_ATTRIBUTE = "ieum.tokenError";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                AuthUser user = jwtProvider.parse(header.substring(7).trim());
                var authentication = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtProvider.TokenException e) {
                request.setAttribute(TOKEN_ERROR_ATTRIBUTE, e.isExpired() ? "EXPIRED" : "INVALID");
            }
        }
        chain.doFilter(request, response);
    }
}
