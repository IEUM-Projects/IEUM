package com.ieum.ieum.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /api/config/map
 * 화면(JavaScript)이 카카오맵 SDK를 불러올 때 필요한 JavaScript 키를 전달한다.
 *
 * 왜 이렇게 하나요?
 * - 키를 index.html에 직접 적으면 GitHub에 올라가 버린다.
 * - 그래서 키는 application-local.yml(커밋 제외)에만 두고, 화면이 실행 중에 이 API로 받아 간다.
 * - JavaScript 키는 원래 브라우저에 보이는 키라 응답으로 내보내도 괜찮다.
 *   대신 카카오 개발자 콘솔에 등록한 도메인(localhost:8080 등)에서만 동작한다.
 * - REST API 키는 서버 전용이라 절대 이런 식으로 내보내면 안 된다.
 */
@RestController
public class MapConfigController {

    private final String javascriptKey;

    public MapConfigController(@Value("${kakao.javascript-key:}") String javascriptKey) {
        this.javascriptKey = javascriptKey;
    }

    public record MapConfig(String javascriptKey) {
    }

    @GetMapping("/api/config/map")
    public MapConfig mapConfig() {
        if (javascriptKey == null || javascriptKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "application-local.yml에 kakao.javascript-key가 없어요.");
        }
        return new MapConfig(javascriptKey);
    }
}
