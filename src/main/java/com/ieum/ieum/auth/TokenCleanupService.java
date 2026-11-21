package com.ieum.ieum.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 만료된 로그인 유지 토큰 정리 (SYS-03)
 *
 * 토큰은 로그아웃·재로그인 때 그 회원 것만 지워져서, 아래 줄은 그대로 쌓인다:
 *   - 재발급(회전)에 쓰여 30초 뒤 만료된 옛 토큰 (화면을 열 때마다 하나씩 생김)
 *   - 다시 오지 않는 회원의 14일 지난 토큰
 * 쓸 수는 없지만(만료) 표가 커지지 않게 매일 새벽 지운다. 1,000개씩 나눠 지워서 잠금을 짧게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenCleanupService {

    static final int BATCH = 1000;

    private final RefreshTokenRepository repository;
    private final TransactionTemplate transaction;
    private final EmailVerificationService emailVerificationService;
    private final com.ieum.ieum.notification.NotificationService notificationService;
    private final com.ieum.ieum.community.photo.PostPhotoService photoService;
    private final com.ieum.ieum.admin.log.AdminActionService adminActions;

    @Scheduled(cron = "${auth.token-cleanup.cron:0 20 4 * * *}", zone = "Asia/Seoul")
    public void scheduled() {
        int deleted = cleanup(LocalDateTime.now());
        if (deleted > 0) log.info("만료된 로그인 유지 토큰 {}개를 지웠어요", deleted);
        // 가입 이메일 인증번호 기록도 하루가 지나면 지운다 (AUTH-02, 개인정보 처리방침 2번)
        int codes = emailVerificationService.cleanup(LocalDateTime.now());
        notificationService.cleanup(LocalDateTime.now());   // 90일 지난 알림 (NOTI-01)
        int photos = photoService.cleanupPending(LocalDateTime.now());   // 하루 지나도록 글에 안 붙인 사진 (COM-02)
        if (photos > 0) log.info("글에 붙이지 않은 사진 {}장을 지웠어요", photos);
        adminActions.cleanup(LocalDateTime.now());   // 1년 지난 관리자 작업 기록 (ADM-03)
        if (codes > 0) log.info("하루 지난 이메일 인증번호 기록 {}개를 지웠어요", codes);
    }

    /** now 이전에 만료된 토큰을 모두 지우고, 지운 개수를 돌려준다 */
    public int cleanup(LocalDateTime now) {
        int total = 0;
        while (true) {
            Integer n = transaction.execute(s -> repository.deleteExpired(now, BATCH));
            int deleted = n == null ? 0 : n;
            total += deleted;
            if (deleted < BATCH) return total;
        }
    }
}
