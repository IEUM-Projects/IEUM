package com.ieum.ieum.global.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.List;

/**
 * 컨트롤러에서 발생한 예외를 모두 받아서 ErrorResponse 형식으로 바꿔 주는 곳.
 * 이 클래스 덕분에 각 컨트롤러는 try-catch 없이 예외를 던지기만 하면 된다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 우리가 직접 던진 예외 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return ResponseEntity.status(e.errorCode().status()).body(ErrorResponse.of(e.errorCode(), e.details()));
    }

    /** @Valid 검사 실패: 칸마다 어떤 문제가 있는지 details에 담는다 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage())
                .distinct()
                .toList();
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_INPUT, details));
    }

    /** JSON 형식이 깨졌거나, 필수 파라미터가 없거나, 숫자 자리에 글자가 온 경우 */
    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    /** 기존 코드(가게 API)가 던지는 ResponseStatusException도 같은 형식으로 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        String code = switch (e.getStatusCode().value()) {
            case 400 -> ErrorCode.INVALID_INPUT.name();
            case 404 -> ErrorCode.NOT_FOUND.name();
            default -> "ERROR";
        };
        String message = e.getReason() != null ? e.getReason() : ErrorCode.INTERNAL_ERROR.message();
        return ResponseEntity.status(e.getStatusCode()).body(new ErrorResponse(code, message, List.of()));
    }

    /** 없는 주소 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.status()).body(ErrorResponse.of(ErrorCode.NOT_FOUND));
    }

    /**
     * 응답을 보내는 중에 브라우저가 연결을 끊은 경우 (응답이 오기 전에 다른 화면으로 이동하는 등).
     * 서버 문제가 아니고 받을 사람도 없으므로, 오류로 기록하지 않고 아무것도 보내지 않는다.
     */
    @ExceptionHandler({HttpMessageNotWritableException.class, AsyncRequestNotUsableException.class})
    public ResponseEntity<ErrorResponse> handleClientGone(Exception e) {
        if (e instanceof AsyncRequestNotUsableException || NestedExceptionUtils.getRootCause(e) instanceof IOException) {
            log.debug("브라우저가 연결을 끊어서 응답을 보내지 못함: {}", e.getMessage());
            return null;
        }
        log.error("응답을 만들지 못한 오류", e);
        return ResponseEntity.internalServerError().body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    /** 예상하지 못한 오류: 사용자에게는 일반 문구만, 자세한 내용은 서버 로그에 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리하지 못한 오류", e);
        return ResponseEntity.internalServerError().body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }
}
