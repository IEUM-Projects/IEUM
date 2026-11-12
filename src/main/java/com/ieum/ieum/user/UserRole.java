package com.ieum.ieum.user;

/** 회원 유형 (기획안 Ⅲ장) */
public enum UserRole {
    USER,      // 일반 회원 (여행객)
    RESIDENT,  // 지역 주민 (현지인 인증 완료)
    MERCHANT,  // 상인 (사업자 인증 + 관리자 승인)
    ADMIN      // 관리자
}
