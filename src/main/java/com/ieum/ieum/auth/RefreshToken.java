package com.ieum.ieum.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** refresh_tokens 테이블. 토큰 원본이 아니라 SHA-256 해시만 저장한다 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public RefreshToken(Long userId, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * 회전(재발급)에 쓰인 토큰은 바로 지우지 않고 남은 시간을 grace만큼으로 줄인다.
     * 재발급 응답이 브라우저에 도착하기 전에 화면을 옮기면 새 쿠키가 저장되지 않는데,
     * 이때 브라우저에 남아 있는 옛 토큰으로 잠깐 동안은 다시 재발급받을 수 있게 하려는 것.
     */
    public void shortenTo(LocalDateTime graceUntil) {
        if (expiresAt.isAfter(graceUntil)) {
            this.expiresAt = graceUntil;
        }
    }
}
