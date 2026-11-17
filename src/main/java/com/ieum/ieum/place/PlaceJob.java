package com.ieum.ieum.place;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/** 오래 걸리는 일(좌표 찾기, 카카오맵 연결) 하나의 진행 상황. 관리자 화면이 몇 초마다 읽어 간다 */
public class PlaceJob {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public record View(String kind, String state, int total, int done, int found, int failed,
                       String message, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    final String kind;
    volatile String state = "RUNNING";
    volatile int total;
    final AtomicInteger done = new AtomicInteger();
    final AtomicInteger found = new AtomicInteger();
    final AtomicInteger failed = new AtomicInteger();
    volatile String message;
    final LocalDateTime startedAt = LocalDateTime.now(SEOUL);
    volatile LocalDateTime finishedAt;

    PlaceJob(String kind) {
        this.kind = kind;
    }

    void finish(String state, String message) {
        this.message = message;
        this.finishedAt = LocalDateTime.now(SEOUL);
        this.state = state;
    }

    public View view() {
        return new View(kind, state, total, done.get(), found.get(), failed.get(), message, startedAt, finishedAt);
    }
}
