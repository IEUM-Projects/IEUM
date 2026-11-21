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

    /** 이메일을 인증한 때 (AUTH-02, 카카오 회원은 null) */
    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

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

    /** 동의한 이용약관·개인정보 처리방침 판 (0 = 동의 전, POL-01) */
    @Column(name = "agreed_policy_version", nullable = false)
    private int agreedPolicyVersion;

    @Column(name = "agreed_at")
    private LocalDateTime agreedAt;

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

    /** 카카오 등 소셜 로그인 회원 만들기. 이메일·비밀번호 없이 가입 방법과 그 서비스의 회원 번호로 구분한다 */
    public static User createSocial(AuthProvider provider, String providerId, String nickname) {
        User user = new User();
        user.nickname = nickname;
        user.role = UserRole.USER;
        user.provider = provider;
        user.providerId = providerId;
        user.status = UserStatus.ACTIVE;
        return user;
    }

    /** 가입 화면에서 이메일 인증번호를 맞힘 (AUTH-02) */
    public void markEmailVerified(LocalDateTime when) {
        this.emailVerifiedAt = when;
    }

    /** 이용약관·개인정보 수집·이용·만 14세 이상에 동의 (POL-01) */
    public void agree(int policyVersion, LocalDateTime now) {
        this.agreedPolicyVersion = policyVersion;
        this.agreedAt = now;
    }

    /** 지금 판에 아직 동의하지 않았는지 */
    public boolean needsAgreement(int policyVersion) {
        return agreedPolicyVersion < policyVersion;
    }

    /**
     * 회원 탈퇴 (POL-01): 개인정보를 바로 지운다.
     * 행은 남기고(제보 기록이 이 회원 번호를 가리켜서) 이메일·비밀번호·카카오 회원 번호·관심 지역을 비우고,
     * 닉네임은 다른 사람이 다시 쓸 수 있게 '탈퇴회원+번호'로 바꾼다.
     */
    public void withdraw() {
        this.email = null;
        this.password = null;
        this.providerId = null;
        this.homeSigunguCode = null;
        this.nickname = "탈퇴" + Long.toString(id, 36);
        this.status = UserStatus.WITHDRAWN;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
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

    /** 내 정보에서 비밀번호를 바꿨을 때 (AUTH-04): 지금 비밀번호를 맞혔으니 틀린 횟수도 0으로 */
    public void changePassword(String encodedPassword) {
        this.password = encodedPassword;
        this.failedLoginCount = 0;
    }

    /**
     * 비밀번호 찾기로 새 비밀번호를 정했을 때 (AUTH-03): 로그인 잠금도 풀고,
     * 메일로 받은 번호를 맞혔으니 이메일도 인증된 것으로 본다.
     */
    public void resetPassword(String encodedPassword, LocalDateTime now) {
        this.password = encodedPassword;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        if (this.emailVerifiedAt == null) this.emailVerifiedAt = now;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }
}
