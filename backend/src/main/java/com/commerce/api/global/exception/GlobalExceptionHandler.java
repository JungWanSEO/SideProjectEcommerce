package com.commerce.api.global.exception;

import com.commerce.api.global.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 전역 예외 처리기.
 * 컨트롤러 어디서든 예외가 터지면 여기로 모여, 일관된 ApiResponse 형태로 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    /** 그 외 예상 못 한 예외 → 500 (원인을 반드시 로그로 남긴다) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleEtc(Exception e) {
        log.error("처리되지 않은 예외 발생", e);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.error("서버 오류가 발생했습니다."));
    }
}
