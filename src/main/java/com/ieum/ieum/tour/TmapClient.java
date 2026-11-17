package com.ieum.ieum.tour;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TMAP 보행자 경로 API (SK open API, 서버 전용 appKey)
 * POST {pedestrian-url}?version=1  본문: startX(경도), startY(위도), endX, endY, startName, endName
 * 응답: GeoJSON FeatureCollection. 첫 Feature(Point)에 totalDistance(m), totalTime(초), LineString들이 실제 걷는 길.
 */
@Component
public class TmapClient {

    /** 걷는 길 하나: 거리(m), 시간(초), 길 좌표 [위도, 경도] */
    public record Route(int distanceM, int seconds, List<double[]> path) {
    }

    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String appKey;
    private final String url;

    public TmapClient(@Value("${tmap.app-key:}") String appKey,
                      @Value("${tmap.pedestrian-url:https://apis.openapi.sk.com/tmap/routes/pedestrian}") String url) {
        this.appKey = appKey == null ? "" : appKey.trim();
        this.url = url;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(6));    // 화면이 기다리는 호출
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean hasKey() {
        return !appKey.isBlank();
    }

    public Route pedestrian(double fromLat, double fromLng, double toLat, double toLng) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("startX", String.valueOf(fromLng));
        body.put("startY", String.valueOf(fromLat));
        body.put("endX", String.valueOf(toLng));
        body.put("endY", String.valueOf(toLat));
        body.put("startName", "출발");
        body.put("endName", "도착");
        JsonNode root;
        try {
            String text = http.post().uri(url + "?version=1").header("appKey", appKey)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsString(body)).retrieve().body(String.class);
            root = json.readTree(text == null ? "{}" : text);
        } catch (RuntimeException e) {   // 연결·시간 초과·4xx/5xx(한도 초과 포함)·잘못된 JSON
            throw new TmapException("TMAP 보행자 경로 실패: " + e.getClass().getSimpleName());
        }
        JsonNode features = root.path("features");
        if (!features.isArray() || features.isEmpty()) throw new TmapException("TMAP 보행자 경로: 결과 없음");
        JsonNode first = features.path(0).path("properties");
        int distance = first.path("totalDistance").asInt(-1);
        int seconds = first.path("totalTime").asInt(-1);
        if (distance < 0 || seconds < 0) throw new TmapException("TMAP 보행자 경로: 거리·시간 없음");
        List<double[]> path = new ArrayList<>();
        for (JsonNode f : features.values()) {
            if (!"LineString".equals(f.path("geometry").path("type").asString(""))) continue;
            for (JsonNode c : f.path("geometry").path("coordinates").values()) {
                double lng = c.path(0).asDouble(Double.NaN);
                double lat = c.path(1).asDouble(Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lng)) continue;
                double[] p = {Math.round(lat * 1e6) / 1e6, Math.round(lng * 1e6) / 1e6};
                double[] last = path.isEmpty() ? null : path.getLast();
                if (last == null || last[0] != p[0] || last[1] != p[1]) path.add(p);   // 이어지는 점 겹침 제거
            }
        }
        if (path.size() < 2) throw new TmapException("TMAP 보행자 경로: 길 좌표 없음");
        return new Route(distance, seconds, path);
    }

    public static class TmapException extends RuntimeException {
        public TmapException(String message) {
            super(message);
        }
    }
}
