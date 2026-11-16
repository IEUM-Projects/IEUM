package com.ieum.ieum.global.error;

import java.util.List;

/**
 * 서비스 규칙을 어겼을 때 던지는 예외.
 * 예: throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
 * GlobalExceptionHandler가 받아서 정해진 JSON 형식으로 응답한다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<String> details;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, List.of());
    }

    public BusinessException(ErrorCode errorCode, List<String> details) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.details = List.copyOf(details);
    }

    public ErrorCode errorCode() { return errorCode; }
    public List<String> details() { return details; }
}
