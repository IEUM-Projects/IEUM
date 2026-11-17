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
import java.util.ArrayList;
import java.util.List;

/**
 * 카카오 로컬 API (서버 전용 REST API 키 사용) - 장소 이름(키워드) 검색만 쓴다
 * - 걷기 코스의 출발점 고르기 (TOUR-01)
 * - 관리자가 가게를 카카오맵 장소와 잇기 (DATA-05)
 *
 * 카카오 로컬 API 이용 조건 (DATA-05):
 * 결과는 그때그때 보여 주기만 하고 저장하지 않는다 (좌표·주소·전화번호·카테고리, 잠깐 저장하는 캐시도 안 됨).
 * 저장해도 되는 것은 장소 ID(카카오맵 링크용)뿐이다. 저장하는 좌표·지역은 브이월드(VWorldClient)로 찾는다.
 */
@Component
public class KakaoLocalClient {

    /** 장소 검색 결과 하나 */
    public record Place(String id, String name, String category, String address, double lat, double lng) {
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

    /** 장소 이름(키워드)으로 찾기. 카카오가 정한 정확도 순, 최대 size곳 */
    public List<Place> keyword(String query, int size) {
        return keyword(query, size, null, null, null);
    }

    /** 장소 이름으로 찾기. 위치(nearLat, nearLng)를 주면 그 주변 radius(m) 안에서 찾는다 */
    public List<Place> keyword(String query, int size, Double nearLat, Double nearLng, Integer radius) {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(baseUrl + "/v2/local/search/keyword.json")
                .queryParam("query", query).queryParam("size", Math.clamp(size, 1, 15));
        if (nearLat != null && nearLng != null) {
            b.queryParam("y", nearLat).queryParam("x", nearLng);
            if (radius != null) b.queryParam("radius", Math.clamp(radius, 0, 20000));
        }
        JsonNode docs = get(b.encode().build().toUri()).path("documents");
        List<Place> out = new ArrayList<>();
        if (!docs.isArray()) return out;
        for (JsonNode d : docs.values()) {
            try {
                double lat = Double.parseDouble(d.path("y").asString());
                double lng = Double.parseDouble(d.path("x").asString());
                String road = d.path("road_address_name").asString("");
                String category = d.path("category_name").asString("");
                int cut = category.lastIndexOf('>');
                out.add(new Place(d.path("id").asString(""), d.path("place_name").asString(""),
                        cut >= 0 ? category.substring(cut + 1).strip() : category,
                        road.isBlank() ? d.path("address_name").asString("") : road, lat, lng));
            } catch (NumberFormatException e) {
                // 좌표가 없는 결과는 건너뜀
            }
        }
        return out;
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
