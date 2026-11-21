package com.ieum.ieum.user;

/** 회원 상태 */
public enum UserStatus {
    ACTIVE,     // 정상
    SUSPENDED,  // 작성 정지 (로그인과 조회는 가능, 글쓰기 불가)
    WITHDRAWN   // 탈퇴
}
