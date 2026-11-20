package com.ieum.ieum.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * 재발급·로그아웃이 같은 토큰을 동시에 다루지 못하게 행을 잠그고 읽는다 (SYS-02).
     * 먼저 잠근 쪽이 끝날 때까지 다른 쪽은 기다린다 → 재발급 후 로그아웃이면 새 토큰까지 지워지고,
     * 로그아웃 후 재발급이면 토큰이 없어 실패한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String tokenHash);

    /** 로그아웃: 같은 로그인에서 이어진 토큰을 모두 지운다 */
    @Modifying
    @Query("delete from RefreshToken t where t.familyId = :familyId")
    int deleteByFamilyId(@Param("familyId") String familyId);

    /** 회원 탈퇴: 그 회원의 토큰을 모두 지움 (모든 기기에서 로그아웃, POL-01) */
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    /** 비밀번호 바꾸기 (AUTH-04): 지금 쓰는 로그인(묶음)만 남기고 그 회원의 다른 토큰을 지운다 */
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId and (t.familyId is null or t.familyId <> :familyId)")
    int deleteByUserIdExceptFamily(@Param("userId") Long userId, @Param("familyId") String familyId);

    /** 로그인할 때 그 회원의 만료된 토큰을 같이 정리 (테이블이 계속 쌓이지 않게) */
    @Modifying
    @Query("delete from RefreshToken t where t.userId = :userId and t.expiresAt < :now")
    int deleteExpiredByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 매일 정리 (SYS-03): 만료된 토큰을 limit개씩 지운다 (한 번에 너무 많이 잠그지 않게).
     * 회전에 쓰인 토큰(30초 뒤 만료)과, 다시 오지 않는 회원의 14일 지난 토큰이 여기서 지워진다.
     */
    @Modifying
    @Query(value = "DELETE FROM refresh_tokens WHERE expires_at < :now ORDER BY expires_at LIMIT :limit", nativeQuery = true)
    int deleteExpired(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
