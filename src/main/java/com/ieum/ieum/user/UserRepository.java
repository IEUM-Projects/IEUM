package com.ieum.ieum.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 메서드 이름만 정해 두면 Spring Data가 쿼리를 자동으로 만들어 준다 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByNickname(String nickname);

    Optional<User> findByProviderAndProviderId(AuthProvider provider, String providerId);
}
