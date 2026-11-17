package com.ieum.ieum.global.config;

import com.ieum.ieum.auth.oauth.OAuth2LoginHandlers;
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
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * 보안 설정.
 *
 * - 화면과 조회 API, 로그인 관련 API는 누구나
 * - 그 밖의 API는 Access Token(JWT)이 있어야 함
 * - 로그인이 필요하면 401, 권한이 없으면 403을 ErrorResponse JSON으로 응답
 *
 * 카카오 로그인과 세션
 * - 카카오로 갔다가 돌아오는 동안 "이 요청이 우리가 보낸 것인지(state)"를 기억하려면 세션이 잠깐 필요하다.
 *   그래서 STATELESS 대신 IF_REQUIRED(필요할 때만 생성)로 둔다.
 * - 로그인 결과(로그인 상태)는 세션에 저장하지 않는다 (RequestAttributeSecurityContextRepository).
 *   로그인이 끝나면 OAuth2LoginHandlers가 세션을 지우고, 이후 요청은 JWT로만 확인한다.
 *
 * - csrf 끔: 로그인 상태를 쿠키가 아닌 Authorization 헤더(JWT)로 주고받는 방식이라 필요 없음.
 *            Refresh Token 쿠키는 SameSite=Strict + /api/auth 경로 한정으로 따로 보호한다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtProvider jwtProvider,
                                                   OAuth2LoginHandlers oauth2LoginHandlers) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable) // 로그아웃은 우리 API(/api/auth/logout)로 처리
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                .oauth2Login(oauth -> oauth
                        .loginPage("/login.html") // Spring 기본 로그인 화면 대신 우리 화면 (3단계에서 만듦)
                        .successHandler(oauth2LoginHandlers.successHandler())
                        .failureHandler(oauth2LoginHandlers.failureHandler()))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(JsonSecurityHandlers.authenticationEntryPoint())
                        .accessDeniedHandler(JsonSecurityHandlers.accessDeniedHandler()))
                .authorizeHttpRequests(auth -> auth
                        // 화면 파일
                        // (mypage.html도 화면 파일은 누구나 받을 수 있고, 내용은 로그인해야 받는 API로 채운다)
                        .requestMatchers("/", "/index.html", "/map.html", "/store.html", "/login.html", "/signup.html",
                                "/mypage.html", "/festivals.html", "/festival.html", "/market.html", "/admin.html",
                                "/notices.html", "/notice.html", "/admin-notices.html", "/admin-markets.html", "/admin-closures.html", "/search.html", "/themes.html", "/theme.html", "/course.html", "/trip.html", "/news.html",
                                "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error").permitAll()
                        // 카카오 로그인 시작(/oauth2/authorization/kakao)과 돌아오는 주소(/login/oauth2/code/kakao)
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // 누구나 볼 수 있는 조회 API
                        .requestMatchers(HttpMethod.GET, "/api/stores", "/api/stores/**",
                                "/api/festivals", "/api/festivals/**", "/api/markets", "/api/markets/**",
                                "/api/regions", "/api/config/map", "/api/notices", "/api/notices/**",
                                "/api/search", "/api/search/**",
                                "/api/themes", "/api/themes/**", "/api/tour/**", "/api/places/search", "/api/news", "/api/news/**").permitAll()
                        // 가입·로그인·재발급·로그아웃
                        .requestMatchers(HttpMethod.POST, "/api/auth/**").permitAll()
                        // 관리자 API: 토큰 권한으로 한 번 막고, AdminGuard가 DB 권한으로 한 번 더 확인
                        // (admin.html 화면 파일은 누구나 받지만, 내용은 이 API로만 채워진다)
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
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
