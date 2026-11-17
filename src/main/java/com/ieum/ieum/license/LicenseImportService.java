package com.ieum.ieum.license;

import com.ieum.ieum.license.LicenseMatcher.Match;
import com.ieum.ieum.license.LicenseRecord.State;
import com.ieum.ieum.license.StoreLicenseCheck.Result;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 인허가 파일로 백년가게 영업상태 확인 (DATA-02)
 *
 * 관리자가 공공데이터포털에서 받은 '행정안전부_식품_일반음식점/휴게음식점/제과점영업' CSV(시·도별 또는 전국)를 올리면
 *   1) 파일을 한 줄씩 읽으며 우리 가게와 주소(길+건물번호 / 동+번지)가 같은 줄만 모은다 (파일 전체를 메모리에 올리지 않음)
 *   2) 가게마다 설정 순서대로(음식점: 일반음식점 → 휴게음식점) 이름까지 맞춰 영업 중 / 폐업 / 휴업을 정한다
 *   3) 폐업·휴업은 관리자 '확인 대기'에 올린다 (가게를 바로 숨기지 않음)
 * 올린 파일에 없는 시·도의 가게는 바꾸지 않는다 (예: 서울 파일만 올리면 부산 가게는 그대로).
 * 큰 파일은 몇십 초 걸려서 따로 돌리고, 화면은 진행 상황(job)을 물어본다.
 */
@Slf4j
@Service
public class LicenseImportService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 이보다 전에 폐업한 기록만 있으면 '폐업 의심'으로 올리지 않는다 (백년가게 제도 시작: 2018년) */
    static final LocalDate OLD_CLOSURE = LocalDate.of(2018, 1, 1);

    private final LicenseKinds kinds;
    private final StoreLicenseCheckRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "license-import");
        t.setDaemon(true);
        return t;
    });
    private volatile Job job;

    public LicenseImportService(LicenseKinds kinds, StoreLicenseCheckRepository repository,
                                NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction) {
        this.kinds = kinds;
        this.repository = repository;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    // ---------------------------------------------------------------- 진행 상황

    public record FileResult(String name, String kind, long rows, List<String> regions, String error) {
    }

    public record JobView(String state, int fileCount, int fileIndex, String currentFile, long rowsRead,
                          List<FileResult> files, Map<Result, Integer> results, int changed, int skipped,
                          String message, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    static final class Job {
        volatile String state = "RUNNING";
        final int fileCount;
        volatile int fileIndex;
        volatile String currentFile;
        final AtomicLong rowsRead = new AtomicLong();
        final List<FileResult> files = Collections.synchronizedList(new ArrayList<>());
        final Map<Result, Integer> results = Collections.synchronizedMap(new EnumMap<>(Result.class));
        volatile int changed;
        volatile int skipped;
        volatile String message;
        final LocalDateTime startedAt = LocalDateTime.now(SEOUL);
        volatile LocalDateTime finishedAt;

        Job(int fileCount) {
            this.fileCount = fileCount;
        }

        JobView view() {
            return new JobView(state, fileCount, fileIndex, currentFile, rowsRead.get(), List.copyOf(files),
                    Map.copyOf(results), changed, skipped, message, startedAt, finishedAt);
        }
    }

    public JobView current() {
        Job j = job;
        return j == null ? null : j.view();
    }

    public boolean isRunning() {
        Job j = job;
        return j != null && "RUNNING".equals(j.state);
    }

    // ---------------------------------------------------------------- 시작

    /** 올린 파일을 임시 폴더에 받아 두고 따로 돌린다. 이미 돌고 있으면 null */
    public synchronized JobView start(List<MultipartFile> uploads) {
        if (isRunning()) return null;
        Path dir;
        List<Path> paths = new ArrayList<>();
        List<String> names = new ArrayList<>();
        try {
            dir = Files.createTempDirectory("ieum-license");
            int n = 0;
            for (MultipartFile f : uploads) {
                if (f.isEmpty()) continue;
                Path p = dir.resolve("f" + (n++) + ".csv");
                f.transferTo(p);
                paths.add(p);
                // 파일 이름은 화면에 보여 주기만 한다 (경로로 쓰지 않음: 운영체제 글자 설정에 따라 한글 경로가 오류 날 수 있음)
                String original = f.getOriginalFilename();
                names.add(original == null || original.isBlank() ? "파일 " + n : original.replaceAll(".*[\\\\/]", ""));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Job j = new Job(paths.size());
        job = j;
        worker.submit(() -> {
            try {
                run(j, paths, names);
            } catch (RuntimeException e) {
                log.error("인허가 파일 확인 실패", e);
                j.state = "FAILED";
                j.message = "확인 중 오류가 났어요: " + e.getClass().getSimpleName();
            } finally {
                j.finishedAt = LocalDateTime.now(SEOUL);
                for (Path p : paths) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 임시 파일은 다음에 운영체제가 지운다
                    }
                }
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException ignored) {
                    // 위와 같음
                }
            }
        });
        return j.view();
    }

    // ---------------------------------------------------------------- 읽기

    record Target(long id, String name, String category, String road, String jibun, String region, boolean closed) {
    }

    /** 확인 대상: 지도에 보이는 가게 + 관리자가 폐업 처리한 가게 */
    List<Target> targets() {
        return jdbc.query("""
                SELECT id, name, category, road_address, jibun_address, info_status
                FROM stores WHERE type = 'CENTURY' AND (is_visible = 1 OR info_status = 'CLOSED') ORDER BY id
                """, new MapSqlParameterSource(), (rs, i) -> {
            String road = rs.getString("road_address");
            String jibun = rs.getString("jibun_address");
            return new Target(rs.getLong("id"), rs.getString("name"), rs.getString("category"), road, jibun,
                    region(road != null ? road : jibun), "CLOSED".equals(rs.getString("info_status")));
        });
    }

    /**
     * 인허가 확인 결과로 가게 정보 채우기 (DATA-05, 23_kakao_cleanup.sql 의 4번과 같음)
     * - 분류가 비어 있는 백년가게: 인허가에서 '영업 중'인 업종으로 (일반음식점 → 음식점, 휴게음식점·제과점 → 카페·간식)
     * - 인허가에서 '영업 중'인 가게: 영업 정보 미확인 → 영업 확인됨 (관리자가 직접 정한 상태는 그대로)
     */
    public void fillFromLicenses() {
        MapSqlParameterSource none = new MapSqlParameterSource();
        int categories = jdbc.update("""
                UPDATE stores s
                JOIN store_license_checks c ON c.store_id = s.id
                SET s.category = CASE WHEN c.license_kind = 'general_restaurants' THEN '음식점' ELSE '카페·간식' END
                WHERE s.type = 'CENTURY' AND s.category IS NULL
                  AND c.result = 'OPEN'
                  AND c.license_kind IN ('general_restaurants', 'rest_restaurants', 'bakeries')
                """, none);
        int verified = jdbc.update("""
                UPDATE stores s
                JOIN store_license_checks c ON c.store_id = s.id AND c.result = 'OPEN'
                SET s.info_status = 'VERIFIED'
                WHERE s.info_status = 'UNVERIFIED'
                  AND NOT EXISTS (SELECT 1 FROM store_edit_logs l WHERE l.store_id = s.id AND l.field = 'INFO_STATUS')
                """, none);
        if (categories + verified > 0) log.info("인허가로 채움: 분류 {}곳, 영업 확인 {}곳", categories, verified);
    }

    public long countTargets() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM stores WHERE type = 'CENTURY' AND (is_visible = 1 OR info_status = 'CLOSED')",
                new MapSqlParameterSource(), Long.class);
        return n == null ? 0 : n;
    }

    private void run(Job j, List<Path> paths, List<String> names) {
        List<Target> targets = targets();
        // 주소 열쇠 → 가게 (같은 열쇠의 가게가 여럿일 수 있음)
        Map<String, List<Target>> byKey = new HashMap<>();
        for (Target t : targets) {
            String r = LicenseMatcher.streetKey(t.road());
            String b = LicenseMatcher.jibunKey(t.jibun());
            if (r != null) byKey.computeIfAbsent("r" + r, k -> new ArrayList<>()).add(t);
            if (b != null) byKey.computeIfAbsent("j" + b, k -> new ArrayList<>()).add(t);
        }
        Map<Long, List<LicenseRecord>> found = new HashMap<>();
        Map<String, Set<String>> covered = new HashMap<>();   // 종류 id → 파일에 나온 시·도
        for (int i = 0; i < paths.size(); i++) {
            j.fileIndex = i + 1;
            j.currentFile = names.get(i);
            j.files.add(readFile(j, paths.get(i), names.get(i), byKey, found, covered));
        }
        if (covered.isEmpty()) {
            j.state = "FAILED";
            j.message = "읽을 수 있는 인허가 파일이 없어요. 아래 파일별 안내를 확인해 주세요.";
            return;
        }
        j.currentFile = null;
        decide(j, targets, found, covered);
        fillFromLicenses();
        j.state = "DONE";
        j.message = "확인을 마쳤어요.";
        log.info("인허가 파일 확인: 파일 {}개, {}줄, 결과 {}, 바뀐 가게 {}곳", paths.size(), j.rowsRead.get(), j.results, j.changed);
    }

    private FileResult readFile(Job j, Path path, String name, Map<String, List<Target>> byKey,
                                Map<Long, List<LicenseRecord>> found, Map<String, Set<String>> covered) {
        long rows = 0;
        Map<String, Integer> kindCount = new HashMap<>();
        Set<String> regions = new TreeSet<>();
        try (LicenseCsv csv = new LicenseCsv(path)) {
            List<String> row;
            while ((row = csv.next()) != null) {
                rows++;
                if ((rows & 4095) == 0) j.rowsRead.addAndGet(4096);
                String id = csv.get(row, "id");
                LicenseKinds.Kind kind = id == null ? null : kinds.byCode(kindCode(id)).orElse(null);
                if (kind == null) continue;
                kindCount.merge(kind.getId(), 1, Integer::sum);
                String road = csv.get(row, "road");
                String jibun = csv.get(row, "jibun");
                String region = region(road != null ? road : jibun);
                if (region != null) {
                    regions.add(region);
                    covered.computeIfAbsent(kind.getId(), k -> new HashSet<>()).add(region);
                }
                List<Target> hit = new ArrayList<>(2);
                String r = LicenseMatcher.streetKey(road);
                if (r != null) hit.addAll(byKey.getOrDefault("r" + r, List.of()));
                String b = LicenseMatcher.jibunKey(jibun);
                if (b != null) for (Target t : byKey.getOrDefault("j" + b, List.of())) if (!hit.contains(t)) hit.add(t);
                if (hit.isEmpty()) continue;
                String statusName = csv.get(row, "statusName");
                String detail = csv.get(row, "detailName");
                String text = statusName == null ? detail : detail == null || statusName.contains(detail) ? statusName : statusName + " (" + detail + ")";
                LicenseRecord rec = new LicenseRecord(kind.getId(), id, csv.get(row, "name"), road, jibun, text,
                        state(csv.get(row, "statusCode"), text), date(csv.get(row, "closed")));
                for (Target t : hit) {
                    if (kind.covers(t.category())) found.computeIfAbsent(t.id(), k -> new ArrayList<>()).add(rec);
                }
            }
        } catch (LicenseCsv.FormatException e) {
            j.rowsRead.addAndGet(rows & 4095);
            return new FileResult(name, null, rows, List.of(), e.getMessage());
        } catch (IOException e) {
            j.rowsRead.addAndGet(rows & 4095);
            return new FileResult(name, null, rows, List.of(), "파일을 읽지 못했어요: " + e.getMessage());
        }
        j.rowsRead.addAndGet(rows & 4095);
        String kindLabel = kindCount.entrySet().stream().max(Map.Entry.comparingByValue())
                .flatMap(e -> kinds.byId(e.getKey())).map(LicenseKinds.Kind::getLabel).orElse(null);
        String error = kindLabel == null ? "일반음식점·휴게음식점·제과점 인허가 파일이 아닌 것 같아요 (관리번호로 업종을 알 수 없음)." : null;
        return new FileResult(name, kindLabel, rows, List.copyOf(regions), error);
    }

    // ---------------------------------------------------------------- 정하기

    private void decide(Job j, List<Target> targets, Map<Long, List<LicenseRecord>> found, Map<String, Set<String>> covered) {
        LocalDateTime now = LocalDateTime.now(SEOUL);
        List<Runnable> saves = new ArrayList<>();
        int changed = 0, skipped = 0;
        Map<Result, Integer> counts = new LinkedHashMap<>();
        for (Target t : targets) {
            Result result;
            LicenseRecord rec = null;
            String note;
            if (kinds.notTarget(t.category())) {
                result = Result.NOT_TARGET;
                note = "인허가로 확인할 수 없는 업종 (" + (t.category() == null ? "분류 없음" : t.category()) + ")";
            } else {
                // 이 가게가 해당하는 인허가 종류(예: 기타 → 일반음식점·휴게음식점·제과점)의 기록을 모두 모아 한 번에 맞춘다.
                // 한 종류는 폐업, 다른 종류는 영업 중이면(예: 일반음식점을 닫고 제과점으로 다시 냄) 영업 중으로 본다.
                boolean allCovered = true;
                List<LicenseRecord> mine = new ArrayList<>();
                for (LicenseKinds.Kind k : kinds.getKinds()) {
                    if (!k.covers(t.category())) continue;
                    if (t.region() == null || !covered.getOrDefault(k.getId(), Set.of()).contains(t.region())) {
                        allCovered = false;
                        continue;
                    }
                    for (LicenseRecord r : found.getOrDefault(t.id(), List.of())) if (r.kind().equals(k.getId())) mine.add(r);
                }
                Match m = LicenseMatcher.match(t.name(), t.road(), t.jibun(), mine);
                if (m.result() == LicenseMatcher.Result.CLOSED && m.record().closedOn() != null
                        && m.record().closedOn().isBefore(OLD_CLOSURE)) {
                    // 백년가게 제도(2018년) 전에 닫은 옛 인허가만 있음 → 지금 가게는 다른 인허가로 영업 중일 가능성이 커서 올리지 않는다
                    m = new Match(LicenseMatcher.Result.NOT_FOUND, null,
                            "2018년 전 폐업 기록만 있음 (" + m.record().closedOn() + ", " + m.record().name() + ")");
                }
                if (m.result() == LicenseMatcher.Result.NOT_FOUND) m = null;
                if (m == null && !allCovered) {   // 이 가게 지역·업종 파일을 아직 다 안 올림 → 그대로 둔다
                    skipped++;
                    continue;
                }
                if (m == null) {
                    result = Result.NOT_FOUND;
                    Match again = LicenseMatcher.match(t.name(), t.road(), t.jibun(), mine);
                    note = again.result() == LicenseMatcher.Result.CLOSED ? "2018년 전 폐업 기록만 있음 (" + again.record().closedOn() + ")"
                            : "올린 인허가 파일에서 주소·이름이 맞는 곳을 찾지 못함";
                } else {
                    result = Result.valueOf(m.result().name());
                    rec = m.record();
                    note = m.note();
                }
            }
            counts.merge(result, 1, Integer::sum);
            final Result fr = result;
            final LicenseRecord frec = rec;
            final String fnote = note;
            saves.add(() -> {
                StoreLicenseCheck c = repository.findForUpdate(t.id()).orElseGet(() -> new StoreLicenseCheck(t.id()));
                c.record(fr, frec, fnote, t.closed(), now);
                repository.save(c);
            });
            changed++;
        }
        for (int i = 0; i < saves.size(); i += 200) {
            List<Runnable> part = saves.subList(i, Math.min(i + 200, saves.size()));
            transaction.executeWithoutResult(s -> part.forEach(Runnable::run));
        }
        j.results.putAll(counts);
        j.changed = changed;
        j.skipped = skipped;
    }

    // ---------------------------------------------------------------- 작은 도우미

    /** 관리번호 "3000000-101-2004-00052" → "101" */
    static String kindCode(String id) {
        int a = id.indexOf('-');
        int b = a < 0 ? -1 : id.indexOf('-', a + 1);
        return b < 0 ? null : id.substring(a + 1, b);
    }

    /** 주소 첫 낱말 → 시·도 짧은 이름 ("서울특별시"·"서울" → "서울", "경상북도"·"경북" → "경북") */
    static String region(String address) {
        if (address == null || address.isBlank()) return null;
        String s = address.trim().split("\\s+")[0];
        if (s.startsWith("전남광주")) return "전남광주";
        Map<String, String> full = Map.of("충청북도", "충북", "충청남도", "충남", "전라북도", "전북", "전라남도", "전남",
                "경상북도", "경북", "경상남도", "경남");
        if (full.containsKey(s)) return full.get(s);
        return s.length() >= 2 ? s.substring(0, 2) : null;
    }

    /** 영업상태: 01 영업/정상, 02 휴업, 03 폐업, 04 취소/말소/만료/정지/중지. 코드가 없으면 이름으로 */
    static State state(String code, String text) {
        if (code != null) {
            switch (code) {
                case "01": return State.OPEN;
                case "02": return State.SUSPENDED;
                case "03", "04": return State.CLOSED;
                default: break;
            }
        }
        String t = text == null ? "" : text;
        if (t.contains("폐업") || t.contains("취소") || t.contains("말소")) return State.CLOSED;
        if (t.contains("휴업")) return State.SUSPENDED;
        if (t.contains("영업") || t.contains("정상")) return State.OPEN;
        return State.OTHER;
    }

    static LocalDate date(String s) {
        if (s == null) return null;
        String d = s.replaceAll("[^0-9]", "");
        if (d.length() < 8) return null;
        try {
            return LocalDate.parse(d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6, 8));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
