package com.ieum.ieum.global.security;

import com.ieum.ieum.global.error.ErrorCode;
import com.ieum.ieum.global.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 로그인이 필요하거나(401) 권한이 없을 때(403)
 * Spring 기본 응답 대신 우리 ErrorResponse JSON으로 응답한다.
 */
public final class JsonSecurityHandlers {

    private JsonSecurityHandlers() {
    }

    public static AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> {
            Object tokenError = request.getAttribute(JwtAuthenticationFilter.TOKEN_ERROR_ATTRIBUTE);
            ErrorCode code = "EXPIRED".equals(tokenError) ? ErrorCode.TOKEN_EXPIRED
                    : "INVALID".equals(tokenError) ? ErrorCode.TOKEN_INVALID
                    : ErrorCode.UNAUTHORIZED;
            write(response, code);
        };
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> write(response, ErrorCode.FORBIDDEN);
    }

    private static void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(ErrorResponse.of(code).toJson());
    }
}
