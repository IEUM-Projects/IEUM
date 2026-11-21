package com.ieum.ieum.auth.oauth;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 카카오 회원이 탈퇴하면 카카오와 이음의 '연결 끊기' (KAKAO-UNLINK)
 *
 * 그대로 두면 회원의 카카오계정 '연결된 서비스'에 이음이 남고, 다시 로그인할 때 동의 화면 없이 바로 들어온다.
 * 이음은 카카오 접근 토큰을 보관하지 않으므로, 앱 어드민 키로 회원번호를 지정해 끊는다.
 *   POST https://kapi.kakao.com/v1/user/unlink
 *   Authorization: KakaoAK {어드민 키}
 *   target_id_type=user_id&target_id={카카오 회원번호}
 *
 * - 어드민 키(kakao.admin-key)는 application-local.yml 에만 (커밋 금지). 없으면 끊지 않고 경고만 남긴다.
 * - 탈퇴 처리가 DB에 확정된 뒤에(afterCommit) 따로 일꾼 스레드에서 부른다. 카카오가 느리거나 실패해도 탈퇴는 그대로다.
 * - 실패하면 5초, 1분 뒤 두 번 더 해 본다. '이미 끊긴 회원'(-101)은 성공으로 본다.
 * - 카카오 회원번호는 메모리에서만 쓰고 어디에도 남기지 않는다 (로그에는 이음 회원 번호만).
 */
@Slf4j
@Service
public class KakaoUnlinkService {

    static final List<Long> RETRY_SECONDS = List.of(0L, 5L, 60L);

    private final RestClient http;
    private final String adminKey;
    private final String url;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kakao-unlink");
        t.setDaemon(true);
        return t;
    });
    private final List<Long> retrySeconds;

    public KakaoUnlinkService(@Value("${kakao.admin-key:}") String adminKey,
                              @Value("${kakao.unlink-url:https://kapi.kakao.com/v1/user/unlink}") String url,
                              @Value("${kakao.unlink-retry-seconds:0,5,60}") List<Long> retrySeconds) {
        this.adminKey = adminKey == null ? "" : adminKey.trim();
        this.url = url;
        this.retrySeconds = retrySeconds == null || retrySeconds.isEmpty() ? RETRY_SECONDS : List.copyOf(retrySeconds);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory).build();
        if (this.adminKey.isEmpty()) {
            log.warn("카카오 어드민 키(kakao.admin-key)가 없어요. 카카오 회원이 탈퇴해도 카카오 연결 끊기를 하지 못해요. 운영 서버에서는 넣어 주세요.");
        }
    }

    public boolean enabled() {
        return !adminKey.isEmpty();
    }

    /**
     * 탈퇴 처리 트랜잭션 안에서 부른다. 커밋된 뒤에 연결 끊기를 시작한다 (트랜잭션이 없으면 바로).
     * kakaoId 는 user.withdraw() 로 지우기 전에 꺼내 둔 값.
     */
    public void unlinkAfterCommit(String kakaoId, long userId) {
        if (kakaoId == null || kakaoId.isBlank()) return;
        if (!enabled()) {
            log.warn("카카오 연결 끊기를 건너뛰었어요 (어드민 키 없음): 회원 {}", userId);
            return;
        }
        Runnable start = () -> attempt(kakaoId, userId, 0);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    start.run();
                }
            });
        } else {
            start.run();
        }
    }

    private void attempt(String kakaoId, long userId, int tryIndex) {
        worker.schedule(() -> {
            Result r = call(kakaoId);
            switch (r) {
                case OK -> log.info("카카오 연결 끊기 완료: 회원 {}", userId);
                case ALREADY -> log.info("카카오 연결은 이미 끊겨 있어요: 회원 {}", userId);
                case BAD_KEY -> log.error("카카오 연결 끊기 실패: 어드민 키가 맞지 않아요 (kakao.admin-key 확인). 회원 {}", userId);
                case RETRY -> {
                    if (tryIndex + 1 < retrySeconds.size()) {
                        attempt(kakaoId, userId, tryIndex + 1);
                    } else {
                        log.error("카카오 연결 끊기를 {}번 해 봤지만 실패했어요: 회원 {}. 회원에게 카카오계정 설정에서 직접 끊도록 안내해 주세요.",
                                retrySeconds.size(), userId);
                    }
                }
            }
        }, retrySeconds.get(tryIndex), TimeUnit.SECONDS);
    }

    enum Result { OK, ALREADY, BAD_KEY, RETRY }

    Result call(String kakaoId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", kakaoId);
        try {
            http.post().uri(url)
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
            return Result.OK;
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString();
            // -101: 이 앱과 연결되지 않은 회원 (이미 끊김). -401/-2(키 문제)는 다시 해도 소용없다
            if (body.contains("-101")) return Result.ALREADY;
            if (e.getStatusCode().value() == 401 || body.contains("\"code\":-401")) return Result.BAD_KEY;
            log.warn("카카오 연결 끊기 응답 {}: {}", e.getStatusCode().value(), body.length() > 200 ? body.substring(0, 200) : body);
            return Result.RETRY;
        } catch (RuntimeException e) {
            log.warn("카카오 연결 끊기 요청 실패: {}", e.getMessage());
            return Result.RETRY;
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdown();
    }
}
