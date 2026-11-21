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

    /**
     * 같은 로그인에서 이어진 토큰 묶음 (SYS-02). 로그인할 때 새로 만들고, 재발급한 토큰은 그대로 물려받는다.
     * 로그아웃은 이 묶음을 통째로 지운다. (11_schema_token_family.sql 이전에 만든 토큰은 비어 있음)
     */
    @Column(name = "family_id", columnDefinition = "char(36)")
    private String familyId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public RefreshToken(Long userId, String familyId, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    /** 묶음이 없는 옛 토큰을 재발급할 때, 이 토큰도 새 묶음에 넣는다 (로그아웃 때 함께 지워지도록) */
    public void joinFamily(String familyId) {
        if (this.familyId == null) {
            this.familyId = familyId;
        }
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
