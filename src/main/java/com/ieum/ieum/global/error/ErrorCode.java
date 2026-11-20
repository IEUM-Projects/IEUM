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
    AGREEMENT_REQUIRED(HttpStatus.FORBIDDEN, "이용약관과 개인정보 처리방침에 동의한 뒤 이용할 수 있어요."),
    WITHDRAW_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "비밀번호가 맞지 않아요."),
    WITHDRAW_CONFIRM_REQUIRED(HttpStatus.BAD_REQUEST, "확인을 위해 '탈퇴'를 입력해 주세요."),
    ADMIN_CANNOT_WITHDRAW(HttpStatus.BAD_REQUEST, "관리자는 탈퇴할 수 없어요. 먼저 관리자 권한을 다른 회원에게 넘겨 주세요."),

    // 가게·제보
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "가게를 찾을 수 없어요."),
    MARKET_NOT_FOUND(HttpStatus.NOT_FOUND, "시장을 찾을 수 없어요."),
    DUPLICATE_REPORT(HttpStatus.CONFLICT, "오늘 이미 같은 내용으로 제보했어요. 내일 다시 보낼 수 있어요."),
    TOO_MANY_REPORTS(HttpStatus.TOO_MANY_REQUESTS, "오늘은 제보를 더 보낼 수 없어요. 내일 다시 시도해 주세요."),

    // 관리자 (ADM-01)
    REPORT_ALREADY_REVIEWED(HttpStatus.CONFLICT, "이미 처리된 제보가 있어요. 목록을 새로 불러와 주세요."),
    ADDRESS_NOT_FOUND(HttpStatus.BAD_REQUEST, "주소로 위치를 찾지 못했어요. 도로명 주소를 확인해 주세요."),
    MAP_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지금은 주소로 위치를 찾을 수 없어요. 잠시 후 다시 시도해 주세요."),

    // 공지사항 (NOT-01)
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "공지를 찾을 수 없어요."),
    TOO_MANY_PINNED(HttpStatus.CONFLICT, "상단 고정은 3개까지 할 수 있어요. 다른 공지의 고정을 먼저 풀어 주세요."),

    // 전통시장 시설 연결 (DATA-01)
    MARKET_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "시설 정보를 찾을 수 없어요."),
    MARKET_ALREADY_LINKED(HttpStatus.CONFLICT, "이 시장은 이미 다른 시설 정보와 이어져 있어요. 그쪽 연결을 먼저 풀어 주세요."),

    // 폐업 의심 가게 (DATA-02)
    CLOSURE_NOT_FOUND(HttpStatus.NOT_FOUND, "확인 결과를 찾을 수 없어요."),
    CLOSURE_ALREADY_REVIEWED(HttpStatus.CONFLICT, "이미 처리된 가게예요. 목록을 새로 불러와 주세요."),
    LICENSE_CHECK_RUNNING(HttpStatus.CONFLICT, "지금 파일을 확인하고 있어요. 끝난 뒤에 다시 올려 주세요."),
    PLACE_JOB_RUNNING(HttpStatus.CONFLICT, "좌표 찾기나 카카오맵 연결을 하고 있어요. 끝난 뒤에 다시 눌러 주세요."),

    // 시장 이야기 (MKT-02)
    STORY_NOT_FOUND(HttpStatus.NOT_FOUND, "아직 저장한 이야기가 없어요. 먼저 저장해 주세요.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
