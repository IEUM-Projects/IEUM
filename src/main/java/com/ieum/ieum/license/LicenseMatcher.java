package com.ieum.ieum.license;

import com.ieum.ieum.license.LicenseRecord.State;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 백년가게와 인허가 데이터를 맞춘다 (DATA-02).
 *
 * 같은 가게로 보는 조건: 주소가 같고(도로명 '길 이름 + 건물번호' 또는 지번 '동·리 + 번지') 이름도 비슷함.
 * 같은 가게로 보이는 인허가가 여러 건이면(주인이 바뀌어 새로 낸 경우 등)
 *   하나라도 영업 중이면 → 영업 중, 아니면 휴업, 모두 폐업이면 → 폐업.
 * 그래서 옛 인허가가 폐업으로 남아 있어도 새 인허가가 영업 중이면 폐업 의심으로 올리지 않는다.
 */
public final class LicenseMatcher {

    private LicenseMatcher() {
    }

    public enum Result { OPEN, CLOSED, SUSPENDED, NOT_FOUND }

    public record Match(Result result, LicenseRecord record, String note) {
    }

    public static Match match(String storeName, String storeRoad, String storeJibun, List<LicenseRecord> candidates) {
        String name = normName(storeName);
        String road = streetKey(storeRoad);
        String jibun = jibunKey(storeJibun);
        List<Scored> same = candidates.stream()
                .filter(r -> r.state() != State.OTHER)
                .map(r -> score(r, name, road, jibun))
                .filter(s -> s.address != null && s.nameScore >= 0.6)
                .toList();
        if (same.isEmpty()) return new Match(Result.NOT_FOUND, null, candidates.isEmpty() ? "검색 결과 없음" : "주소·이름이 맞는 인허가 없음 (" + candidates.size() + "건 중)");
        Comparator<Scored> best = Comparator.comparingDouble((Scored s) -> -s.nameScore);
        Optional<Scored> open = same.stream().filter(s -> s.record.state() == State.OPEN).min(best);
        if (open.isPresent()) return new Match(Result.OPEN, open.get().record, note(open.get(), same.size()));
        Optional<Scored> suspended = same.stream().filter(s -> s.record.state() == State.SUSPENDED).min(best);
        if (suspended.isPresent()) return new Match(Result.SUSPENDED, suspended.get().record, note(suspended.get(), same.size()));
        Scored closed = same.stream().max(Comparator.comparing((Scored s) -> s.record.closedOn(), Comparator.nullsFirst(Comparator.naturalOrder()))).orElseThrow();
        return new Match(Result.CLOSED, closed.record, note(closed, same.size()));
    }

    private record Scored(LicenseRecord record, String address, double nameScore) {
    }

    private static Scored score(LicenseRecord r, String name, String road, String jibun) {
        String how = null;
        if (road != null && road.equals(streetKey(r.roadAddress()))) how = "도로명";
        else if (jibun != null && jibun.equals(jibunKey(r.jibunAddress()))) how = "지번";
        return new Scored(r, how, nameSimilarity(name, normName(r.name())));
    }

    private static String note(Scored s, int count) {
        String n = s.address + " 주소 같음, 이름 " + (s.nameScore >= 1 ? "같음" : "비슷함");
        return count > 1 ? n + " (같은 가게로 보이는 인허가 " + count + "건)" : n;
    }

    // ---------------------------------------------------------------- 이름

    private static final Pattern NAME_NOISE = Pattern.compile("\\(주\\)|㈜|주식회사|유한회사|농업회사법인|\\(유\\)|[\\s\\p{Punct}·ㆍ「」『』（）]");

    static String normName(String s) {
        if (s == null) return "";
        return NAME_NOISE.matcher(s).replaceAll("").toLowerCase();
    }

    /** 1: 같음, 0.9: 한쪽이 다른 쪽을 품음(2글자 이상), 그 밖: 두 글자씩 묶어 겹치는 정도 */
    static double nameSimilarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 1;
        if (Math.min(a.length(), b.length()) >= 2 && (a.contains(b) || b.contains(a))) return 0.9;
        Set<String> x = bigrams(a), y = bigrams(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> both = new HashSet<>(x);
        both.retainAll(y);
        return 2.0 * both.size() / (x.size() + y.size());
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) set.add(s.substring(i, i + 2));
        return set;
    }

    // ---------------------------------------------------------------- 주소

    private static final Pattern REGION = Pattern.compile("(특별시|광역시|특별자치시|특별자치도|도|시|군|구)$");
    private static final Pattern ROAD_PART = Pattern.compile("(로|길)\\d");
    private static final Pattern STREET = Pattern.compile("(\\S+(?:로|길)(?:\\s?\\d+번?길)?)\\s*(\\d+(?:-\\d+)?)");
    private static final Pattern JIBUN = Pattern.compile("(\\S+(?:동|리|가|읍|면)\\d*가?)\\s+(산?\\d+(?:-\\d+)?)");

    /**
     * 도로명 주소 → '길 이름 + 건물번호' (시·도, 시·군·구 이름은 뺌: 구 이름이 바뀐 곳도 맞춰짐)
     * "서울특별시 중구 남대문시장4길 21, 1층 (남창동)" → "남대문시장4길21"
     */
    static String streetKey(String address) {
        if (address == null || address.isBlank()) return null;
        StringBuilder rest = new StringBuilder();
        for (String part : address.replace(",", " ").split("\\s+")) {
            if (REGION.matcher(part).find() && !ROAD_PART.matcher(part).find()) continue;
            rest.append(part).append(' ');
        }
        Matcher m = STREET.matcher(rest);
        return m.find() ? (m.group(1) + m.group(2)).replaceAll("\\s+", "") : null;
    }

    /** 지번 주소 → '동·리 + 번지' ("서울특별시 중구 남창동 60-1" → "남창동60-1") */
    static String jibunKey(String address) {
        if (address == null || address.isBlank()) return null;
        Matcher m = JIBUN.matcher(address);
        return m.find() ? (m.group(1) + m.group(2)).replaceAll("\\s+", "") : null;
    }
}
