package com.ieum.ieum.festival;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공공데이터포털 API 두 개를 부른다.
 *
 * 1) 전국문화축제표준데이터  api.data.go.kr/openapi/tn_pubr_public_cltur_fstvl_api
 * 2) 한국관광공사 국문 관광정보 서비스  apis.data.go.kr/B551011/KorService2
 *    - /searchFestival2 (기간으로 행사 목록), /detailCommon2 (소개글·홈페이지)
 *    - /locationBasedList2 (좌표 주변 관광지·문화시설, TOUR-01)
 *
 * 인증키(public-data.service-key)는 application-local.yml에만 둔다.
 * 인증키가 들어간 요청 주소는 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class PublicDataClient {

    private static final int PAGE_SIZE = 1000;   // 두 API 모두 한 번에 최대 1000건
    private static final int MAX_PAGES = 20;     // 혹시 모를 무한 반복 방지
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient http;
    /** 화면이 기다리는 호출(주변 관광지)용: 오래 기다리지 않는다 (TOUR-01) */
    private final RestClient quickHttp;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String serviceKey;
    private final String standardUrl;
    private final String tourUrl;

    public PublicDataClient(@Value("${public-data.service-key:}") String serviceKey,
                            @Value("${public-data.standard-festival-url}") String standardUrl,
                            @Value("${public-data.tour-url}") String tourUrl) {
        // 'Encoding' 키(%2B 같은 글자가 들어간 것)를 넣어도 동작하도록 한 번 풀어 둔다. 보낼 때 다시 인코딩된다.
        String key = serviceKey == null ? "" : serviceKey.trim();
        this.serviceKey = key.contains("%") ? URLDecoder.decode(key, StandardCharsets.UTF_8) : key;
        this.standardUrl = standardUrl;
        this.tourUrl = tourUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().requestFactory(factory).build();
        SimpleClientHttpRequestFactory quick = new SimpleClientHttpRequestFactory();
        quick.setConnectTimeout(Duration.ofSeconds(5));
        quick.setReadTimeout(Duration.ofSeconds(8));
        this.quickHttp = RestClient.builder().requestFactory(quick).build();
    }

    public boolean hasKey() {
        return !serviceKey.isBlank();
    }

    // ---------------------------------------------------------------- 전국문화축제표준데이터

    public List<FestivalRecord> fetchStandard() {
        List<FestivalRecord> result = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        for (int page = 1; page <= MAX_PAGES && (page - 1) * PAGE_SIZE < total; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("pageNo", String.valueOf(page));
            params.put("numOfRows", String.valueOf(PAGE_SIZE));
            params.put("type", "json");
            Page body = call(standardUrl, params, "표준데이터");
            total = body.totalCount();
            for (JsonNode item : body.items()) {
                result.add(standardRecord(item));
            }
            if (body.items().isEmpty()) break;
        }
        log.info("표준데이터 축제 {}건 받음", result.size());
        return result;
    }

    private FestivalRecord standardRecord(JsonNode i) {
        return new FestivalRecord("STANDARD",
                FestivalText.cut(text(i, "fstvlNm"), 200),
                FestivalText.cut(text(i, "opar"), 300),
                FestivalText.date(text(i, "fstvlStartDate")),
                FestivalText.date(text(i, "fstvlEndDate")),
                FestivalText.cut(text(i, "fstvlCo"), 4000),
                FestivalText.cut(text(i, "mnnstNm"), 200),
                FestivalText.cut(text(i, "auspcInsttNm"), 200),
                FestivalText.cut(text(i, "suprtInsttNm"), 300),
                FestivalText.cut(text(i, "phoneNumber"), 50),
                FestivalText.homepage(text(i, "homepageUrl")),
                FestivalText.cut(text(i, "rdnmadr"), 300),
                FestivalText.cut(text(i, "lnmadr"), 300),
                FestivalText.latitude(text(i, "latitude")),
                FestivalText.longitude(text(i, "longitude")),
                null, null, null,
                FestivalText.cut(text(i, "insttNm"), 100),
                FestivalText.date(text(i, "referenceDate")));
    }

    // ---------------------------------------------------------------- 한국관광공사

    /** since 이후에 끝나는(또는 진행 중인) 행사 목록 */
    public List<FestivalRecord> fetchTour(LocalDate since) {
        List<FestivalRecord> result = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        for (int page = 1; page <= MAX_PAGES && (page - 1) * PAGE_SIZE < total; page++) {
            Map<String, String> params = tourParams();
            params.put("pageNo", String.valueOf(page));
            params.put("numOfRows", String.valueOf(PAGE_SIZE));
            params.put("arrange", "A");
            params.put("eventStartDate", since.format(YYYYMMDD));
            Page body = call(tourUrl + "/searchFestival2", params, "관광공사 행사");
            total = body.totalCount();
            for (JsonNode item : body.items()) {
                result.add(tourRecord(item));
            }
            if (body.items().isEmpty()) break;
        }
        log.info("관광공사 행사 {}건 받음", result.size());
        return result;
    }

    private FestivalRecord tourRecord(JsonNode i) {
        String addr1 = FestivalText.clean(text(i, "addr1"));
        String addr2 = FestivalText.clean(text(i, "addr2"));
        return new FestivalRecord("TOUR",
                FestivalText.cut(text(i, "title"), 200),
                FestivalText.cut(addr2, 300),            // 관광공사는 addr2에 장소 이름을 넣는 경우가 많다
                FestivalText.date(text(i, "eventstartdate")),
                FestivalText.date(text(i, "eventenddate")),
                null, null, null, null,
                FestivalText.cut(text(i, "tel"), 50),
                null,
                FestivalText.cut(addr1, 300),
                null,
                FestivalText.latitude(text(i, "mapy")),   // mapy = 위도, mapx = 경도
                FestivalText.longitude(text(i, "mapx")),
                FestivalText.homepage(text(i, "firstimage")),
                FestivalText.cut(text(i, "cpyrhtDivCd"), 10),
                FestivalText.cut(text(i, "contentid"), 20),
                "한국관광공사",
                FestivalText.date(firstEight(text(i, "modifiedtime"))));   // 20261023172552 → 2026-10-23
    }

    private static String firstEight(String s) {
        return s == null || s.length() < 8 ? null : s.substring(0, 8);
    }

    public record TourDetail(String overview, String homepage, String tel) {
    }

    /** 행사 하나의 소개글·홈페이지 (관광공사 상세) */
    public Optional<TourDetail> tourDetail(String contentId) {
        return tourDetail(contentId, false);
    }

    /** quick=true 면 화면이 기다리는 호출이라 8초까지만 기다린다 (시장 이야기, MKT-02) */
    public Optional<TourDetail> tourDetail(String contentId, boolean quick) {
        Map<String, String> params = tourParams();
        params.put("contentId", contentId);
        Page body = call(quick ? quickHttp : http, tourUrl + "/detailCommon2", params, "관광공사 상세");
        if (body.items().isEmpty()) return Optional.empty();
        JsonNode i = body.items().getFirst();
        return Optional.of(new TourDetail(
                FestivalText.cut(FestivalText.plainText(text(i, "overview")), 4000),
                FestivalText.homepage(text(i, "homepage")),
                FestivalText.cut(text(i, "tel"), 50)));
    }

    /** 관광지 한 곳 (관광공사 위치 기반 조회, TOUR-01) */
    public record TourSpotRecord(String contentId, int contentType, String title, String address,
                                 Double latitude, Double longitude,
                                 String imageUrl, String imageLicense) {
    }

    /**
     * 좌표 주변 관광정보 (관광공사 /locationBasedList2, 가까운 순)
     * contentTypeId: 12 관광지, 14 문화시설. 한 쪽 100건, 최대 maxPages쪽
     */
    public List<TourSpotRecord> nearbySpots(double lat, double lng, int radius, int contentTypeId, int maxPages) {
        List<TourSpotRecord> result = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            Map<String, String> params = tourParams();
            params.put("mapX", String.valueOf(lng));     // 관광공사: mapX = 경도, mapY = 위도
            params.put("mapY", String.valueOf(lat));
            params.put("radius", String.valueOf(radius));
            params.put("contentTypeId", String.valueOf(contentTypeId));
            params.put("arrange", "E");                  // 거리순
            params.put("numOfRows", "100");
            params.put("pageNo", String.valueOf(page));
            Page body = call(quickHttp, tourUrl + "/locationBasedList2", params, "관광공사 주변 관광정보");
            for (JsonNode i : body.items()) {
                Double y = FestivalText.latitude(text(i, "mapy"));
                Double x = FestivalText.longitude(text(i, "mapx"));
                String id = text(i, "contentid");
                if (id == null || x == null || y == null) continue;
                String addr = String.join(" ", java.util.stream.Stream.of(text(i, "addr1"), text(i, "addr2"))
                        .filter(v -> v != null && !v.isBlank()).toList());
                result.add(new TourSpotRecord(FestivalText.cut(id, 20), contentTypeId, FestivalText.cut(text(i, "title"), 200),
                        FestivalText.cut(addr.isBlank() ? null : addr, 300), y, x,
                        FestivalText.homepage(text(i, "firstimage")), FestivalText.cut(text(i, "cpyrhtDivCd"), 10)));
            }
            if (body.items().isEmpty() || page * 100 >= body.totalCount()) break;
        }
        return result;
    }

    private Map<String, String> tourParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MobileOS", "ETC");
        params.put("MobileApp", "ieum");
        params.put("_type", "json");
        return params;
    }

    // ---------------------------------------------------------------- 공통

    private record Page(List<JsonNode> items, int totalCount) {
    }

    /** 호출하고 응답에서 items 목록과 전체 건수를 꺼낸다. 실패하면 PublicDataException */
    private Page call(String url, Map<String, String> params, String what) {
        return call(http, url, params, what);
    }

    private Page call(RestClient client, String url, Map<String, String> params, String what) {
        if (!hasKey()) throw new PublicDataException(what + ": application-local.yml에 public-data.service-key가 없어요.");
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url).queryParam("serviceKey", "{serviceKey}");
        params.forEach(builder::queryParam);
        // {serviceKey} 자리에 키를 넣으면서 +, /, = 같은 글자를 안전하게 인코딩한다
        URI uri = builder.encode().buildAndExpand(Map.of("serviceKey", serviceKey)).toUri();

        String raw;
        try {
            raw = client.get().uri(uri).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new PublicDataException(what + " 호출 실패: " + e.getClass().getSimpleName() + " " + firstLine(e.getMessage()));
        }
        if (raw == null || raw.isBlank()) throw new PublicDataException(what + ": 빈 응답");
        String trimmed = raw.trim();
        if (trimmed.startsWith("<")) {
            // 인증키 오류 등은 json을 요청해도 XML로 온다. 구체적인 이유(returnAuthMsg)를 먼저 보여 준다
            String reason = xmlValue(trimmed, "returnAuthMsg");
            if (reason == null) reason = xmlValue(trimmed, "errMsg");
            if (reason == null) reason = xmlValue(trimmed, "resultMsg");
            String hint = reason != null && reason.contains("SERVICE_KEY")
                    ? " (application-local.yml의 public-data.service-key에 공공데이터포털 '일반 인증키'를 넣었는지, 활용 승인이 됐는지 확인해 주세요)" : "";
            throw new PublicDataException(what + " 오류: " + (reason != null ? reason : firstLine(trimmed)) + hint);
        }
        JsonNode root;
        try {
            root = json.readTree(trimmed);
        } catch (RuntimeException e) {
            throw new PublicDataException(what + ": JSON이 아닌 응답 " + firstLine(trimmed));
        }
        JsonNode response = root.has("response") ? root.path("response") : root;
        String code = text(response.path("header"), "resultCode");
        if (code != null && !code.equals("00") && !code.equals("0000")) {
            throw new PublicDataException(what + " 오류: " + code + " " + text(response.path("header"), "resultMsg"));
        }
        JsonNode body = response.path("body");
        return new Page(items(body.path("items")), number(body.path("totalCount")));
    }

    private static String xmlValue(String xml, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        return m.find() && !m.group(1).isBlank() ? m.group(1).trim() : null;
    }

    /** items가 [..] 이거나 {"item":[..]} 이거나 {"item":{..}} 이거나 "" (0건) 인 경우를 모두 처리 */
    private static List<JsonNode> items(JsonNode items) {
        JsonNode list = items.isObject() ? items.path("item") : items;
        List<JsonNode> result = new ArrayList<>();
        if (list.isArray()) list.values().forEach(result::add);
        else if (list.isObject()) result.add(list);
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : FestivalText.clean(v.asString());
    }

    private static int number(JsonNode node) {
        try {
            return node.isMissingNode() ? 0 : Integer.parseInt(node.asString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 로그용 한 줄. 연결 오류 메시지에는 요청 주소가 들어 있어서 인증키 부분을 가린다 */
    private static String firstLine(String s) {
        if (s == null) return "";
        String line = s.lines().findFirst().orElse("").replaceAll("(?i)serviceKey=[^&\\s\"]*", "serviceKey=***");
        return line.length() > 200 ? line.substring(0, 200) : line;
    }

    public static class PublicDataException extends RuntimeException {
        public PublicDataException(String message) {
            super(message);
        }
    }
}
