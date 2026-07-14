package com.commerce.api.global.exception;

import com.commerce.api.global.common.ApiResponse;
import com.commerce.api.global.ratelimit.RateLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 전역 예외 처리기.
 * 컨트롤러 어디서든 예외가 터지면 여기로 모여, 일관된 ApiResponse 형태로 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 레이트 리밋 초과 → 429 + <b>{@code Retry-After}</b>(초).
     *
     * <p>BusinessException보다 <b>더 구체적인 타입</b>이라 스프링이 이 핸들러를 먼저 고른다.
     * 429만 주면 클라이언트는 언제 다시 와야 할지 몰라 즉시 재시도(=부하 증폭)하거나 과하게 오래 기다린다 →
     * 표준 헤더로 "몇 초 뒤"를 알려준다(RFC 9110).
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(RateLimitExceededException e) {
        return ResponseEntity
                .status(e.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(ApiResponse.error(e.getMessage()));
    }

    /** 비즈니스 예외 → 예외가 가진 상태코드로 응답 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        return ResponseEntity
                .status(e.getStatus())
                .body(ApiResponse.error(e.getMessage()));
    }

    /** @Valid 검증 실패 → 400, 첫 번째 검증 메시지를 반환 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("잘못된 요청입니다.");
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    /** 요청 본문(JSON)이 깨졌거나 형식이 잘못됨 → 400 (클라이언트 오류) */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("요청 본문을 읽을 수 없습니다. JSON 형식과 인코딩(UTF-8)을 확인하세요."));
    }

    /**
     * 경로/쿼리 파라미터 타입 불일치 → 400 (잘못된 요청, 500 아님).
     * 예: GET /api/products/abc — {id} 가 Long인데 "abc"가 와서 변환 실패. 클라이언트가 잘못된 URL을 보낸 것.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error("요청 파라미터 '" + e.getName() + "' 의 형식이 올바르지 않습니다."));
    }

    /**
     * 존재하지 않는 경로 → 404 (500 아님).
     *
     * <p>Spring 6/Boot 3.2+ 는 매핑 없는 요청에 {@link NoResourceFoundException}("No static resource ...")을
     * 던지는데, 이게 아래 catch-all(Exception)에 걸려 <b>500</b>으로 뭉개지고 있었다. 그러면
     * ①클라이언트가 오탐 500을 받고 ②운영에선 5xx 알림룰(Prometheus)이 단순 오타 경로에도 울린다.
     * (예: actuator 노출을 줄여 /actuator/prometheus 가 사라지면 404여야 하는데 500이 났다.)
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("요청하신 경로를 찾을 수 없습니다."));
    }

    /** 그 외 예상 못 한 예외 → 500 (원인을 반드시 로그로 남긴다) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleEtc(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.error("서버 오류가 발생했습니다."));
    }
}
