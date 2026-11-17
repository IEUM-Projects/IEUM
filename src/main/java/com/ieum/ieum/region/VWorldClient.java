package com.ieum.ieum.region;

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
import java.util.Set;

/**
 * 브이월드(국토교통부 공간정보 오픈플랫폼) 주소 검색 API 2.0 (DATA-05)
 *
 * - 주소 → 좌표 (getcoord): 도로명(type=road)으로 먼저, 못 찾으면 지번(type=parcel)으로
 * - 좌표 → 주소 (getAddress): 원본에 좌표만 있는 시장·축제의 시·군·구, 가게의 지번 주소(인허가 확인에 씀)
 * 응답의 행정구역 코드(level4LC / level4AC: 도로명·법정동·행정동 코드) 앞 5자리가 시·군·구 코드다.
 *
 * 카카오 로컬 API는 결과(좌표·주소)를 저장할 수 없어서 저장하는 좌표는 모두 이 API로 찾는다.
 * 키: application-local.yml 의 vworld.api-key (커밋 금지). 키가 없으면 좌표 채우기를 건너뛴다.
 * 주소를 못 찾은 것은 빈 값.
 * 잠깐의 서버 오류(502 등 5xx, 연결 끊김, SYSTEM_ERROR)는 1초·3초 쉬고 두 번 더 해 본다. 그래도 안 되면 VWorldException(retryable).
 * 키 문제·하루 한도 초과는 바로 VWorldException(retryable 아님) - 부르는 쪽이 그만두고 다음에 이어서 한다.
 */
@Component
public class VWorldClient {

    /** 찾은 위치. sigunguCode 는 브이월드가 준 코드 앞 5자리 (없으면 null, 이음 지역 표에 있는지는 확인 전) */
    public record Hit(double lat, double lng, String sigunguCode, String text) {
    }

    /** 그만둬야 하는 오류 (키·한도·서버). 나머지 오류(주소 형식 등)는 '못 찾음'으로 본다 */
    private static final Set<String> FATAL = Set.of("INVALID_KEY", "INCORRECT_KEY", "UNAVAILABLE_KEY", "OVER_REQUEST_LIMIT");
    /** 잠깐 뒤 다시 해 볼 만한 브이월드 오류 */
    private static final Set<String> TRANSIENT = Set.of("SYSTEM_ERROR", "UNKNOWN_ERROR");
    private static final long[] RETRY_WAIT_MS = {1000, 3000};

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String key;
    private final String url;
    private final String domain;

    public VWorldClient(@Value("${vworld.api-key:}") String key,
                        @Value("${vworld.url:https://api.vworld.kr/req/address}") String url,
                        @Value("${vworld.domain:}") String domain) {
        this.key = key == null ? "" : key.trim();
        this.url = url;
        this.domain = domain == null ? "" : domain.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean hasKey() {
        return !key.isBlank();
    }

    /**
     * 주소 → 좌표. 도로명으로 먼저 찾고, 못 찾으면 지번으로.
     * 도로명 모양의 주소를 지번(type=parcel)으로 물으면 브이월드가 502를 주는 일이 있어서,
     * 지번 찾기는 한 번만 해 보고 잠깐의 오류는 '못 찾음'으로 본다 (키·한도 문제는 그대로 던짐).
     */
    public Optional<Hit> geocode(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        for (String type : new String[]{"road", "parcel"}) {
            UriComponentsBuilder b = base().queryParam("request", "getcoord").queryParam("address", address.strip())
                    .queryParam("type", type).queryParam("refine", "true").queryParam("simple", "false");
            JsonNode res;
            if ("road".equals(type)) {
                res = call(b);
            } else {
                try {
                    res = once(b.encode().build().toUri());
                } catch (VWorldException e) {
                    if (!e.isRetryable()) throw e;
                    res = null;
                }
            }
            if (res == null) continue;
            JsonNode point = res.path("result").path("point");
            Double lat = number(point.path("y")), lng = number(point.path("x"));
            if (lat == null || lng == null || !inKorea(lat, lng)) continue;
            JsonNode refined = res.path("refined");
            return Optional.of(new Hit(lat, lng, sigungu(refined.path("structure")), refined.path("text").asString(null)));
        }
        return Optional.empty();
    }

    /** 좌표 → 시·군·구 코드 (앞 5자리). 못 찾으면 빈 값 */
    public Optional<String> sigunguAt(double lat, double lng) {
        JsonNode res = reverse(base().queryParam("request", "getAddress").queryParam("point", lng + "," + lat)
                .queryParam("type", "both").queryParam("zipcode", "false").queryParam("simple", "false"));
        if (res == null) return Optional.empty();
        JsonNode result = res.path("result");
        for (JsonNode r : result.isArray() ? result.values() : java.util.List.of(result)) {
            String code = sigungu(r.path("structure"));
            if (code != null) return Optional.of(code);
        }
        return Optional.empty();
    }

    /** 좌표 → 지번 주소 글자 ('서울특별시 종로구 관훈동 100-4'). 못 찾으면 빈 값 */
    public Optional<String> parcelAt(double lat, double lng) {
        JsonNode res = reverse(base().queryParam("request", "getAddress").queryParam("point", lng + "," + lat)
                .queryParam("type", "parcel").queryParam("zipcode", "false").queryParam("simple", "false"));
        if (res == null) return Optional.empty();
        JsonNode result = res.path("result");
        for (JsonNode r : result.isArray() ? result.values() : java.util.List.of(result)) {
            String text = r.path("text").asString("").strip();
            if (!text.isBlank()) return Optional.of(text.length() > 200 ? text.substring(0, 200) : text);
        }
        return Optional.empty();
    }

    /**
     * 좌표 → 주소는 한 번만 묻는다. 주소가 없는 자리(산·바다·공원 가운데 등)에서 브이월드가 502를 주는 일이 있어서
     * 잠깐의 오류는 '못 찾음'으로 본다 (키·한도 문제는 그대로 던짐).
     */
    private JsonNode reverse(UriComponentsBuilder b) {
        try {
            return once(b.encode().build().toUri());
        } catch (VWorldException e) {
            if (!e.isRetryable()) throw e;
            return null;
        }
    }

    private UriComponentsBuilder base() {
        UriComponentsBuilder b = UriComponentsBuilder.fromUriString(url)
                .queryParam("service", "address").queryParam("version", "2.0")
                .queryParam("crs", "epsg:4326").queryParam("format", "json").queryParam("key", key);
        if (!domain.isBlank()) b.queryParam("domain", domain);
        return b;
    }

    /** status OK → response 노드, NOT_FOUND·가벼운 오류 → null, 잠깐의 오류 → 다시 해 보고, 그래도 안 되면 예외 */
    private JsonNode call(UriComponentsBuilder b) {
        URI uri = b.encode().build().toUri();
        VWorldException last = null;
        for (int attempt = 0; attempt <= RETRY_WAIT_MS.length; attempt++) {
            if (attempt > 0) pause(RETRY_WAIT_MS[attempt - 1]);
            try {
                return once(uri);
            } catch (VWorldException e) {
                if (!e.isRetryable()) throw e;
                last = e;
            }
        }
        throw last;
    }

    /** 부르는 사이 최소 간격 (가게·시장·축제 채우기가 동시에 돌아도 브이월드에 몰아서 부르지 않도록) */
    static final long MIN_INTERVAL_MS = 30;
    private long lastCallAt;

    private synchronized void throttle() {
        long wait = lastCallAt + MIN_INTERVAL_MS - System.currentTimeMillis();
        if (wait > 0) pause(wait);
        lastCallAt = System.currentTimeMillis();
    }

    JsonNode once(URI uri) {
        throttle();
        JsonNode root;
        try {
            String body = http.get().uri(uri).retrieve().body(String.class);
            root = body == null ? json.createObjectNode() : json.readTree(body);
        } catch (org.springframework.web.client.HttpClientErrorException e) {   // 4xx: 다시 해도 같음
            throw new VWorldException("브이월드 주소 검색 실패: HTTP " + e.getStatusCode().value(), false);
        } catch (RuntimeException e) {   // 5xx(BadGateway 등), 연결 끊김·시간 초과, 잘못된 JSON
            throw new VWorldException("브이월드 주소 검색 실패: " + e.getClass().getSimpleName(), true);
        }
        JsonNode res = root.path("response");
        String status = res.path("status").asString("");
        if ("OK".equals(status)) return res;
        if ("NOT_FOUND".equals(status)) return null;
        String code = res.path("error").path("code").asString("");
        if (TRANSIENT.contains(code)) throw new VWorldException("브이월드 주소 검색 오류: " + code, true);
        if ("ERROR".equals(status) && !FATAL.contains(code)) return null;
        throw new VWorldException("브이월드 주소 검색 오류: " + (code.isBlank() ? status : code), false);
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VWorldException("브이월드 주소 검색을 멈춤 (서버 종료)", false);
        }
    }

    /** 구조 정보의 코드(도로명코드 12자리, 법정동·행정동 코드 10자리) 앞 5자리 */
    static String sigungu(JsonNode structure) {
        for (String f : new String[]{"level4LC", "level4AC"}) {
            String v = structure.path(f).asString("").strip();
            if (v.matches("\\d{10,12}")) return v.substring(0, 5);
        }
        return null;
    }

    private static Double number(JsonNode n) {
        try {
            String s = n.asString("");
            return s.isBlank() ? null : Double.parseDouble(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean inKorea(double lat, double lng) {
        return lat >= 33 && lat <= 39 && lng >= 124 && lng <= 132;
    }

    public static class VWorldException extends RuntimeException {
        private final boolean retryable;

        public VWorldException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        /** 잠깐의 오류였는지 (키·한도 문제가 아님) */
        public boolean isRetryable() {
            return retryable;
        }
    }
}
