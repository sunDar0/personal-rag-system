package com.devbrain.controller;

import java.time.LocalDateTime;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.devbrain.infrastructure.gemini.GeminiException;

import lombok.extern.slf4j.Slf4j;

/**
 * 전역 예외 처리기
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Gemini API 예외 처리
     */
    @ExceptionHandler(GeminiException.class)
    public ResponseEntity<Map<String, Object>> handleGeminiException(GeminiException e) {
        log.error("Gemini API 에러: [{}] {}", e.getErrorType(), e.getMessage());

        HttpStatus status = switch (e.getStatusCode()) {
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 401, 403 -> HttpStatus.UNAUTHORIZED;
            case 400 -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        Map<String, Object> body = Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", status.value(),
                "error", e.getErrorType(),
                "message", e.getMessage(),
                "retryable", e.isRetryable()
        );

        return ResponseEntity.status(status).body(body);
    }

    /**
     * 일반 예외 처리
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception e) {
        log.error("서버 에러: {}", e.getMessage(), e);

        Map<String, Object> body = Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "status", 500,
                "error", "INTERNAL_SERVER_ERROR",
                "message", "서버 내부 오류가 발생했습니다."
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

