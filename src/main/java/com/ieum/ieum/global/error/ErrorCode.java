package com.ieum.ieum.global.error;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전체에서 쓰는 오류 종류.
 * 화면(JavaScript)은 code 값을 보고 어떤 오류인지 구분하고, message는 사용자에게 그대로 보여 준다.
 */
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 후 다시 시도해 주세요."),

    // 인증
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "로그인이 만료되었어요."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "로그인 정보가 올바르지 않아요."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없어요."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 맞지 않아요."),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "로그인을 5번 실패해서 10분 동안 잠겼어요."),
    ACCOUNT_WITHDRAWN(HttpStatus.FORBIDDEN, "탈퇴한 계정이에요."),

    // 회원
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일이에요."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임이에요."),
    WEAK_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호 규칙을 확인해 주세요."),

    // 가게·제보
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "가게를 찾을 수 없어요."),
    MARKET_NOT_FOUND(HttpStatus.NOT_FOUND, "시장을 찾을 수 없어요."),
    DUPLICATE_REPORT(HttpStatus.CONFLICT, "오늘 이미 같은 내용으로 제보했어요. 내일 다시 보낼 수 있어요."),
    TOO_MANY_REPORTS(HttpStatus.TOO_MANY_REQUESTS, "오늘은 제보를 더 보낼 수 없어요. 내일 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
