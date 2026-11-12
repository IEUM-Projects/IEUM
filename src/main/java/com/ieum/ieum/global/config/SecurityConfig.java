package com.ieum.ieum.global.config;

import com.ieum.ieum.global.security.JsonSecurityHandlers;
import com.ieum.ieum.global.security.JwtAuthenticationFilter;
import com.ieum.ieum.global.security.JwtProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 보안 설정.
 *
 * - 화면과 조회 API, 로그인 관련 API는 누구나
 * - 그 밖의 API는 Access Token(JWT)이 있어야 함
 * - 로그인이 필요하면 401, 권한이 없으면 403을 ErrorResponse JSON으로 응답
 *
 * - csrf 끔:     쿠키 로그인 대신 Authorization 헤더(JWT)를 쓰는 방식이라 필요 없음.
 *               Refresh Token 쿠키는 SameSite=Strict + /api/auth 경로 한정으로 따로 보호한다.
 * - STATELESS:   서버가 로그인 상태(세션)를 저장하지 않음
 *                (2단계 카카오 로그인에서 로그인 과정에만 세션을 쓰도록 조정 예정)
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable) // 로그아웃은 우리 API(/api/auth/logout)로 처리
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(JsonSecurityHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(JsonSecurityHandlers.accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        // 화면 파일
                        .requestMatchers("/", "/index.html", "/store.html", "/login.html", "/signup.html",
                                "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error").permitAll()
                        // 누구나 볼 수 있는 조회 API
                        .requestMatchers(HttpMethod.GET, "/api/stores", "/api/stores/**",
                                "/api/regions", "/api/config/map").permitAll()
                        // 가입·로그인·재발급·로그아웃
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        // 나머지는 로그인 필요
                        .anyRequest().authenticated()
                );
        return http.build();
    }

    /** 비밀번호 암호화 (BCrypt: 같은 비밀번호도 매번 다른 값으로 저장되고, 되돌릴 수 없음) */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 로그인은 우리 AuthService가 직접 처리하므로 Spring 기본 계정은 쓰지 않는다.
     * 빈 계정 목록을 등록해 두면 실행할 때마다 나오던 "Using generated security password" 안내가 사라진다.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
