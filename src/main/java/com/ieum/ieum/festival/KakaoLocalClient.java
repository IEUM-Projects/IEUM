package com.ieum.ieum.festival;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * 카카오 로컬 API (서버 전용 REST API 키 사용)
 * - 주소 → 좌표: 원본에 좌표가 없는 축제
 * - 좌표 → 지역 코드: 가게와 같은 지역 코드(regions 표)를 쓰기 위해
 */
@Component
public class KakaoLocalClient {

    public record Point(double lat, double lng) {
    }

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String restApiKey;
    private final String baseUrl;

    public KakaoLocalClient(@Value("${kakao.rest-api-key:}") String restApiKey,
                            @Value("${kakao.local-url:https://dapi.kakao.com}") String baseUrl) {
        this.restApiKey = restApiKey == null ? "" : restApiKey.trim();
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean hasKey() {
        return !restApiKey.isBlank();
    }

    /** 주소로 좌표 찾기. 못 찾으면 빈 값 */
    public Optional<Point> geocode(String address) {
        JsonNode docs = get(UriComponentsBuilder.fromUriString(baseUrl + "/v2/local/search/address.json")
                .queryParam("query", address).queryParam("size", 1)
                .encode().build().toUri()).path("documents");
        if (!docs.isArray() || docs.isEmpty()) return Optional.empty();
        JsonNode first = docs.path(0);
        try {
            return Optional.of(new Point(Double.parseDouble(first.path("y").asString()),
                    Double.parseDouble(first.path("x").asString())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** 좌표의 시·군·구 코드 (법정동 코드 앞 5자리). 못 찾으면 빈 값 */
    public Optional<String> sigunguCode(double lat, double lng) {
        JsonNode docs = get(UriComponentsBuilder.fromUriString(baseUrl + "/v2/local/geo/coord2regioncode.json")
                .queryParam("x", lng).queryParam("y", lat)
                .encode().build().toUri()).path("documents");
        if (docs.isArray()) {
            for (JsonNode doc : docs.values()) {
                String code = doc.path("code").asString("");
                if ("B".equals(doc.path("region_type").asString("")) && code.matches("\\d{10}")) {
                    return Optional.of(code.substring(0, 5));
                }
            }
        }
        return Optional.empty();
    }

    private JsonNode get(URI uri) {
        try {
            String body = http.get().uri(uri).header("Authorization", "KakaoAK " + restApiKey)
                    .retrieve().body(String.class);
            return body == null ? json.createObjectNode() : json.readTree(body);
        } catch (RuntimeException e) {   // 연결·인증 오류(RestClientException), 잘못된 JSON 모두
            throw new KakaoException("카카오 로컬 API 실패: " + e.getClass().getSimpleName());
        }
    }

    public static class KakaoException extends RuntimeException {
        public KakaoException(String message) {
            super(message);
        }
    }
}
