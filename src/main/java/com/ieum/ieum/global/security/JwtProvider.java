package com.ieum.ieum.global.security;

import com.ieum.ieum.user.UserRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Access Token(JWT) 발급과 검증.
 *
 * 별도 라이브러리(jjwt) 없이, 이미 들어 있는 Spring Security의 JWT 도구(Nimbus)를 쓴다.
 * 서명 방식: HS256 (서버만 아는 비밀 키 jwt.secret으로 서명)
 *
 * 토큰 안에 들어가는 값
 *   sub  : 회원 번호
 *   role : 회원 유형 (USER, RESIDENT ...)
 *   iss  : "ieum"
 *   exp  : 만료 시각 (발급 후 30분)
 */
@Component
public class JwtProvider {

    public static final String ISSUER = "ieum";
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(30);

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public JwtProvider(@Value("${jwt.secret:}") String secret) {
        SecretKey key = new SecretKeySpec(toKeyBytes(secret), "HmacSHA256");
        this.encoder = NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbusDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER)); // 만료 시각 + 발급자 검사
        this.decoder = nimbusDecoder;
    }

    /** 비밀 키는 32바이트(256비트) 이상이어야 HS256에 안전하다. 짧으면 서버가 아예 시작하지 않게 막는다 */
    private static byte[] toKeyBytes(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("application-local.yml에 jwt.secret을 설정해 주세요.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException notBase64) {
            bytes = secret.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("jwt.secret이 너무 짧아요. 32바이트 이상(Base64 44자 이상)으로 만들어 주세요.");
        }
        return bytes;
    }

    public String createAccessToken(Long userId, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plus(ACCESS_TOKEN_TTL))
                .claim("role", role.name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** 토큰을 검사해서 로그인한 회원 정보를 돌려준다. 문제가 있으면 TokenException */
    public AuthUser parse(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return new AuthUser(Long.valueOf(jwt.getSubject()), UserRole.valueOf(jwt.getClaimAsString("role")));
        } catch (JwtValidationException e) {
            boolean expired = e.getErrors().stream()
                    .anyMatch(error -> error.getDescription() != null && error.getDescription().contains("expired"));
            throw new TokenException(expired);
        } catch (BadJwtException | IllegalArgumentException e) {
            throw new TokenException(false);
        } catch (JwtException e) {
            throw new TokenException(false);
        }
    }

    /** 토큰 검사 실패. expired가 true면 화면은 /api/auth/refresh로 새 토큰을 받으면 된다 */
    public static class TokenException extends RuntimeException {
        private final boolean expired;

        public TokenException(boolean expired) {
            super(expired ? "expired" : "invalid");
            this.expired = expired;
        }

        public boolean isExpired() { return expired; }
    }
}
