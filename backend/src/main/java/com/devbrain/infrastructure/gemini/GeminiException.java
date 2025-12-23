package com.devbrain.infrastructure.gemini;

/**
 * Gemini API 관련 예외
 */
public class GeminiException extends RuntimeException {
    
    private final int statusCode;
    private final String errorType;
    private final boolean retryable;

    public GeminiException(String message, int statusCode, String errorType, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.errorType = errorType;
        this.retryable = retryable;
    }

    public GeminiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;
        this.errorType = "INTERNAL_ERROR";
        this.retryable = false;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorType() {
        return errorType;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Rate Limit 예외 생성
     */
    public static GeminiException rateLimitExceeded(String message) {
        return new GeminiException(
            message != null ? message : "Gemini API Rate Limit 초과. 잠시 후 다시 시도해주세요.",
            429,
            "RATE_LIMIT_EXCEEDED",
            true
        );
    }

    /**
     * 인증 오류 예외 생성
     */
    public static GeminiException unauthorized(String message) {
        return new GeminiException(
            message != null ? message : "Gemini API 인증 실패. API 키를 확인해주세요.",
            401,
            "UNAUTHORIZED",
            false
        );
    }

    /**
     * 잘못된 요청 예외 생성
     */
    public static GeminiException badRequest(String message) {
        return new GeminiException(
            message != null ? message : "잘못된 요청입니다.",
            400,
            "BAD_REQUEST",
            false
        );
    }

    /**
     * 서버 오류 예외 생성
     */
    public static GeminiException serverError(String message) {
        return new GeminiException(
            message != null ? message : "Gemini API 서버 오류가 발생했습니다.",
            500,
            "SERVER_ERROR",
            true
        );
    }
}

