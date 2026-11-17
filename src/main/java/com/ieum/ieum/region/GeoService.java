package com.ieum.ieum.region;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 주소 → 좌표·지역, 좌표 → 지역 (DATA-05)
 *
 * 좌표는 브이월드 주소 검색으로 찾는다 (카카오 결과는 저장할 수 없음).
 * 지역 코드(regions 표)는
 *   1) 주소 글자로 찾기: '경기도 수원시 팔달구 …' → 경기 + 수원시 팔달구 (API를 부르지 않음)
 *   2) 그래도 모르면 브이월드가 준 행정구역 코드 앞 5자리 (이음 지역 표에 있는 코드만)
 * 지역 표에 없는 시·군·구면 null (좌표는 저장해서 지도·코스에는 나온다).
 * 브이월드 키·한도·연결 문제는 VWorldClient.VWorldException 으로 그대로 던진다 (부르는 쪽이 멈추고 다음에 이어서).
 */
@Service
@RequiredArgsConstructor
public class GeoService {

    public record Located(double lat, double lng, String sigunguCode) {
        public String sidoCode() {
            return sigunguCode == null ? null : sigunguCode.substring(0, 2);
        }
    }

    private static final Pattern ROAD = Pattern.compile("^(.+?(?:로|길)\\s?\\d+(?:-\\d+)?)(?:\\D.*)?$");
    private static final Pattern JIBUN = Pattern.compile("^(.+?(?:동|리|가)\\s?(?:산\\s?)?\\d+(?:-\\d+)?)(?:\\D.*)?$");

    private final VWorldClient vworld;
    private final NamedParameterJdbcTemplate jdbc;

    /** 시도 짧은 이름 → (시군구 이름 → 코드). 지역 표는 바뀌지 않아서 처음 한 번만 읽는다 */
    private volatile Map<String, Map<String, String>> regions;
    private volatile java.util.Set<String> codes;

    public boolean available() {
        return vworld.hasKey();
    }

    /**
     * 주소 → 좌표·지역. 괄호·쉼표 뒤를 뺀 주소, 건물번호까지 자른 주소, 원본 주소 순으로 찾는다.
     * 어떤 주소 모양에서만 브이월드가 오류(502 등)를 주는 일이 있어서, 잠깐의 오류는 다음 모양으로 넘어가 본다.
     * 모든 모양이 오류였으면 그 오류를 던진다 (키·한도 문제는 바로 던짐).
     */
    public Optional<Located> locate(String address) {
        VWorldClient.VWorldException error = null;
        for (String candidate : candidates(address)) {
            Optional<VWorldClient.Hit> hit;
            try {
                hit = vworld.geocode(candidate);
            } catch (VWorldClient.VWorldException e) {
                if (!e.isRetryable()) throw e;
                error = e;
                continue;
            }
            if (hit.isPresent()) {
                VWorldClient.Hit h = hit.get();
                String code = regionByText(address);
                if (code == null) code = regionByText(h.text());
                if (code == null) code = known(h.sigunguCode());
                return Optional.of(new Located(h.lat(), h.lng(), code));
            }
        }
        if (error != null) throw error;
        return Optional.empty();
    }

    /** 이미 좌표가 있는 곳의 지역: 주소 글자로 먼저, 모르면 브이월드 좌표 → 주소 */
    public Optional<String> regionOf(String address, double lat, double lng) {
        String code = regionByText(address);
        if (code != null) return Optional.of(code);
        if (!vworld.hasKey()) return Optional.empty();
        return vworld.sigunguAt(lat, lng).map(this::known);
    }

    /** 좌표의 지번 주소 (인허가 파일과 맞춰 볼 때 씀). 키가 없거나 못 찾으면 빈 값 */
    public Optional<String> parcelAddress(double lat, double lng) {
        if (!vworld.hasKey()) return Optional.empty();
        return vworld.parcelAt(lat, lng);
    }

    /** 지역 표에 있는 코드면 그대로, 아니면 null */
    public String known(String code) {
        load();
        return code != null && codes.contains(code) ? code : null;
    }

    // ---------------------------------------------------------------- 주소 글자 → 지역 코드

    /** '서울특별시 종로구 …', '경기 수원시 팔달구 …', '세종특별자치시 한누리대로 …' → 지역 코드. 모르면 null */
    public String regionByText(String address) {
        if (address == null || address.isBlank()) return null;
        load();
        String[] t = address.replaceAll("\\([^)]*\\)", " ").replace(',', ' ').strip().split("\\s+");
        if (t.length < 2) return null;
        String sido = sido(t[0]);
        Map<String, String> names = sido == null ? null : regions.get(sido);
        if (names == null) return null;
        if (names.size() == 1 && "세종".equals(sido)) return names.values().iterator().next();
        if (t.length >= 3 && names.containsKey(t[1] + " " + t[2])) return names.get(t[1] + " " + t[2]);
        if (names.containsKey(t[1])) return names.get(t[1]);
        return null;   // '수원시'만 있고 구가 없는 주소처럼 하나로 못 정하는 경우
    }

    /** 시도 이름 → 지역 표의 짧은 이름 (서울특별시 → 서울, 전라남도·광주광역시 → 전남광주 …) */
    String sido(String first) {
        load();
        String s = first.strip();
        String shortName;
        if (s.startsWith("전남광주")) shortName = "전남광주";
        else if (s.startsWith("충청북")) shortName = "충북";
        else if (s.startsWith("충청남")) shortName = "충남";
        else if (s.startsWith("전라북")) shortName = "전북";
        else if (s.startsWith("전라남")) shortName = "전남";
        else if (s.startsWith("경상북")) shortName = "경북";
        else if (s.startsWith("경상남")) shortName = "경남";
        else shortName = s.length() >= 2 ? s.substring(0, 2) : s;
        if (regions.containsKey(shortName)) return shortName;
        // 전라남도와 광주광역시가 합쳐진 지역 표 (전남광주)
        if (("전남".equals(shortName) || "광주".equals(shortName)) && regions.containsKey("전남광주")) return "전남광주";
        return null;
    }

    private void load() {
        if (regions != null) return;
        synchronized (this) {
            if (regions != null) return;
            Map<String, Map<String, String>> map = new HashMap<>();
            java.util.Set<String> all = new java.util.HashSet<>();
            jdbc.query("SELECT code, sido_name, sigungu_name FROM regions", new MapSqlParameterSource(), rs -> {
                map.computeIfAbsent(rs.getString("sido_name"), k -> new HashMap<>())
                        .put(rs.getString("sigungu_name"), rs.getString("code"));
                all.add(rs.getString("code"));
            });
            codes = all;
            regions = map;
        }
    }

    /**
     * 찾아볼 주소 모양 (겹치면 한 번만): 괄호·쉼표 뒤를 뺀 것 → 건물번호까지 자른 것 → 괄호만 뺀 것 → 원본
     * 예: '대전 중구 대종로480번길 15(은행동)' → '대전 중구 대종로480번길 15' 먼저
     */
    public static List<String> candidates(String address) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (address == null || address.isBlank()) return List.of();
        String a = address.strip();
        String noParen = a.replaceAll("\\s*\\([^)]*\\)", " ").replaceAll("\\s+", " ").strip();
        String noComma = noParen.replaceAll(",.*$", "").strip();
        out.add(noComma);
        for (Pattern p : List.of(ROAD, JIBUN)) {
            Matcher m = p.matcher(noComma);
            if (m.matches()) out.add(m.group(1).strip());
        }
        out.add(noParen);
        out.add(a);
        out.removeIf(String::isBlank);
        return new ArrayList<>(out);
    }
}
