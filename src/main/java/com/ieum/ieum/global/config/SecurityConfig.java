package com.ieum.ieum.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 보안 설정.
 *
 * 지금 단계: 로그인 없이 볼 수 있는 것(화면, 가게 조회 API)만 열고 나머지는 막아 둔다.
 * Phase 2(회원·인증)에서 JWT 필터와 카카오 로그인을 여기에 추가한다.
 *
 * - csrf 끔:     세션·쿠키 대신 JWT를 쓰는 API 서버 방식이라 필요 없음
 * - formLogin 끔: Spring 기본 로그인 화면을 쓰지 않음 (화면은 우리가 직접 만듦)
 * - STATELESS:   서버가 로그인 상태(세션)를 저장하지 않음 (JWT 방식)
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 화면 파일
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/images/**",
                                "/favicon.ico", "/error").permitAll()
                        // 누구나 볼 수 있는 조회 API
                        .requestMatchers(HttpMethod.GET, "/api/stores", "/api/stores/**").permitAll()
                        // 나머지는 로그인 필요 (Phase 2에서 구현)
                        .anyRequest().authenticated()
                );
        return http.build();
    }
}
