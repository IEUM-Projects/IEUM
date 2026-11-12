package com.ieum.ieum.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * users 테이블 (회원).
 * 테이블은 schema_auth.sql로 만들고, ddl-auto: validate가 모양이 맞는지 검사한다.
 *
 * setter를 열지 않고 "무엇을 하는지" 이름이 붙은 메서드(loginSucceeded, changeNickname ...)로만 값을 바꾼다.
 * 이렇게 하면 회원 정보가 어디서 어떻게 바뀌는지 찾기 쉽다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    public static final int MAX_FAILED_LOGIN = 5;
    public static final int LOCK_MINUTES = 10;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100)
    private String email;

    @Column(length = 100)
    private String password;

    @Column(nullable = false, length = 20)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    private AuthProvider provider;

    @Column(name = "provider_id", length = 50)
    private String providerId;

    @Column(name = "home_sigungu_code", columnDefinition = "char(5)")
    private String homeSigunguCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "varchar(10)")
    private UserStatus status;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /** 이메일 가입 회원 만들기. password는 이미 BCrypt로 암호화된 값이어야 한다 */
    public static User createLocal(String email, String encodedPassword, String nickname) {
        User user = new User();
        user.email = email;
        user.password = encodedPassword;
        user.nickname = nickname;
        user.role = UserRole.USER;
        user.provider = AuthProvider.LOCAL;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** 비밀번호가 틀렸을 때: 5번째 실패면 10분 잠그고 횟수를 0으로 되돌린다 */
    public void loginFailed(LocalDateTime now) {
        failedLoginCount++;
        if (failedLoginCount >= MAX_FAILED_LOGIN) {
            lockedUntil = now.plusMinutes(LOCK_MINUTES);
            failedLoginCount = 0;
        }
    }

    public void loginSucceeded(LocalDateTime now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }
}
