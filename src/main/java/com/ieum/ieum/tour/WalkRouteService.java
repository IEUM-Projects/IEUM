package com.ieum.ieum.tour;

import com.ieum.ieum.tour.TmapClient.Route;
import com.ieum.ieum.tour.TmapClient.TmapException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 걷기 코스의 실제 도보 경로 (TOUR-01): 구간마다 TMAP 보행자 경로를 받아 온다.
 *
 * - 같은 구간(좌표를 약 1m 단위로 맞춤)은 24시간 동안 서버 메모리에 둔 결과를 쓴다. DB에는 저장하지 않는다.
 * - 하루 호출 수를 tmap.daily-limit(기본 900)으로 막는다. 넘으면 그날은 직선 어림값.
 * - TMAP이 실패하면 직선 어림값 (화면은 그대로 동작).
 *   · 그 구간만의 문제(400·404, 길이 없는 곳 등)면 그 구간만 직선으로 두고 1시간 동안 그 구간은 다시 묻지 않는다.
 *   · 키 오류·한도 초과·서버 오류·시간 초과면 5분 동안 모든 구간을 부르지 않는다.
 * - 너무 짧은 구간(30m 미만)과 너무 먼 구간(직선 5km 넘음)은 부르지 않는다.
 * - 한 코스의 구간들은 동시에 부른다 (구간 수만큼 기다리지 않게).
 */
@Slf4j
@Service
public class WalkRouteService {

    public static final int MAX_LEGS = 6;
    static final int MIN_M = 30;
    static final int MAX_M = 5000;
    static final int CACHE_SIZE = 3000;
    static final Duration CACHE_TIME = Duration.ofHours(24);
    static final Duration PAUSE_AFTER_FAILURE = Duration.ofMinutes(5);
    static final Duration FAILED_LEG_TIME = Duration.ofHours(1);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 구간 하나. routed=false 이면 path 는 두 끝점뿐이고 distanceM 은 직선 거리, seconds 는 0 (화면이 어림) */
    public record Leg(int distanceM, int seconds, List<double[]> path, boolean routed) {
    }

    /** route 가 null 이면 "이 구간은 TMAP 이 길을 못 찾음" (FAILED_LEG_TIME 동안 다시 묻지 않음) */
    private record Cached(Route route, Instant at) {
    }

    private final TmapClient tmap;
    private final int dailyLimit;
    private final Map<String, Cached> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private LocalDate countDay = LocalDate.now(SEOUL);
    private int countToday = 0;
    private volatile Instant pausedUntil = Instant.EPOCH;

    public WalkRouteService(TmapClient tmap, @Value("${tmap.daily-limit:900}") int dailyLimit) {
        this.tmap = tmap;
        this.dailyLimit = dailyLimit;
    }

    public boolean enabled() {
        return tmap.hasKey();
    }

    /** points[0] → points[1] → ... 구간마다 */
    public List<Leg> legs(List<double[]> points) {
        List<Leg> out = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Leg>> jobs = new ArrayList<>();
            for (int i = 0; i + 1 < points.size(); i++) {
                double[] a = points.get(i);
                double[] b = points.get(i + 1);
                jobs.add(pool.submit(() -> leg(a, b)));
            }
            for (int i = 0; i < jobs.size(); i++) {
                try {
                    out.add(jobs.get(i).get());
                } catch (Exception e) {
                    out.add(straight(points.get(i), points.get(i + 1)));
                }
            }
        }
        return out;
    }

    Leg leg(double[] a, double[] b) {
        int straightM = (int) Math.round(distanceM(a, b));
        if (straightM < MIN_M || straightM > MAX_M || !tmap.hasKey()) return straight(a, b);
        String key = key(a) + ">" + key(b);
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && c.route() == null && c.at().plus(FAILED_LEG_TIME).isAfter(Instant.now())) return straight(a, b);
            if (c != null && c.route() != null && c.at().plus(CACHE_TIME).isAfter(Instant.now())) return routed(c.route());
        }
        if (Instant.now().isBefore(pausedUntil) || !takeQuota()) return straight(a, b);
        try {
            Route r = tmap.pedestrian(a[0], a[1], b[0], b[1]);
            synchronized (cache) {
                cache.put(key, new Cached(r, Instant.now()));
            }
            return routed(r);
        } catch (TmapException e) {
            if (e.onlyThisLeg()) {
                synchronized (cache) {
                    cache.put(key, new Cached(null, Instant.now()));
                }
                log.info("{} → 이 구간만 직선 어림값 ({} → {})", e.getMessage(), key(a), key(b));
            } else {
                pausedUntil = Instant.now().plus(PAUSE_AFTER_FAILURE);
                log.warn("{} → {}분 동안 직선 어림값을 씀", e.getMessage(), PAUSE_AFTER_FAILURE.toMinutes());
            }
            return straight(a, b);
        }
    }

    private synchronized boolean takeQuota() {
        LocalDate today = LocalDate.now(SEOUL);
        if (!today.equals(countDay)) {
            countDay = today;
            countToday = 0;
        }
        if (countToday >= dailyLimit) return false;
        countToday++;
        return true;
    }

    private static Leg routed(Route r) {
        return new Leg(r.distanceM(), r.seconds(), r.path(), true);
    }

    private static Leg straight(double[] a, double[] b) {
        return new Leg((int) Math.round(distanceM(a, b)), 0, List.of(a, b), false);
    }

    private static String key(double[] p) {
        return Math.round(p[0] * 1e5) + "," + Math.round(p[1] * 1e5);
    }

    static double distanceM(double[] a, double[] b) {
        double p1 = Math.toRadians(a[0]);
        double p2 = Math.toRadians(b[0]);
        double h = Math.pow(Math.sin((p2 - p1) / 2), 2)
                + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(b[1] - a[1]) / 2), 2);
        return 2 * 6371008.8 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
