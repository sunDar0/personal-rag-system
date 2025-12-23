package com.devbrain.infrastructure.gemini;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Google Gemini API 클라이언트
 * Rate Limit 처리 및 재시도 로직 포함
 */
@Slf4j
@Component
public class GeminiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.0-flash}")
    private String model;

    @Value("${gemini.embedding-model:text-embedding-004}")
    private String embeddingModel;

    // 재시도 설정
    private static final int MAX_RETRIES = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(2);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    public GeminiClient(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper는 null일 수 없습니다");
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    /**
     * 초기화 후 설정값 검증
     */
    @PostConstruct
    public void validateConfig() {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("⚠️ GOOGLE_API_KEY가 설정되지 않았습니다. Gemini API 호출이 실패합니다.");
        }
        log.info("🔧 Gemini 설정: model={}, embeddingModel={}", model, embeddingModel);
    }

    /**
     * 텍스트 임베딩 생성 (재시도 로직 포함)
     * 
     * @param text 임베딩할 텍스트 (null 또는 빈 문자열 불가)
     * @return 임베딩 벡터
     * @throws GeminiException API 호출 실패 시
     */
    public List<Double> embedText(String text) {
        // 파라미터 검증
        validateApiKeyConfigured();
        if (!StringUtils.hasText(text)) {
            throw GeminiException.badRequest("임베딩할 텍스트가 비어있습니다.");
        }

        String url = String.format("/models/%s:embedContent?key=%s", embeddingModel, apiKey);

        Map<String, Object> requestBody = Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of(
                        "parts", List.of(Map.of("text", text))
                )
        );

        try {
            String response = webClient.post()
                    .uri(Objects.requireNonNull(url, "URL이 null입니다"))
                    .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                    .bodyValue(Objects.requireNonNull(requestBody, "요청 바디가 null입니다"))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
                    .bodyToMono(String.class)
                    .retryWhen(createRetrySpec("임베딩 생성"))
                    .block();

            // 응답 null 체크
            if (response == null) {
                throw GeminiException.serverError("Gemini API 응답이 비어있습니다.");
            }

            JsonNode root = objectMapper.readTree(response);
            JsonNode values = root.path("embedding").path("values");

            if (values.isMissingNode() || !values.isArray()) {
                throw GeminiException.serverError("임베딩 응답 형식이 올바르지 않습니다.");
            }

            return objectMapper.convertValue(values,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
        } catch (GeminiException e) {
            throw e; // 이미 처리된 예외는 그대로 전파
        } catch (Exception e) {
            log.error("임베딩 생성 실패: {}", e.getMessage());
            throw new GeminiException("임베딩 생성 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 텍스트 생성 (스트리밍, 재시도 로직 포함)
     * 
     * @param systemPrompt 시스템 프롬프트 (null 또는 빈 문자열 불가)
     * @param userPrompt 사용자 프롬프트 (null 또는 빈 문자열 불가)
     * @return 생성된 텍스트 스트림
     * @throws GeminiException API 호출 실패 시
     */
    public Flux<String> generateContentStream(String systemPrompt, String userPrompt) {
        // 파라미터 검증
        validateApiKeyConfigured();
        if (!StringUtils.hasText(systemPrompt)) {
            return Flux.error(GeminiException.badRequest("시스템 프롬프트가 비어있습니다."));
        }
        if (!StringUtils.hasText(userPrompt)) {
            return Flux.error(GeminiException.badRequest("사용자 프롬프트가 비어있습니다."));
        }

        String url = String.format("/models/%s:streamGenerateContent?alt=sse&key=%s", model, apiKey);

        Map<String, Object> requestBody = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemPrompt))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", userPrompt))
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.7,
                        "maxOutputTokens", 2048
                )
        );

        return webClient.post()
                .uri(Objects.requireNonNull(url, "URL이 null입니다"))
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .bodyValue(Objects.requireNonNull(requestBody, "요청 바디가 null입니다"))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
                .bodyToFlux(String.class)
                .retryWhen(createRetrySpec("텍스트 생성"))
                .filter(data -> !data.isEmpty())
                .map(this::extractTextFromSseData)
                .filter(text -> text != null && !text.isEmpty())
                .onErrorResume(e -> {
                    if (e instanceof GeminiException) {
                        return Flux.error(e);
                    }
                    log.error("스트리밍 생성 실패: {}", e.getMessage());
                    return Flux.error(new GeminiException("스트리밍 생성 실패: " + e.getMessage(), e));
                });
    }

    /**
     * SSE 데이터에서 텍스트 추출
     */
    private String extractTextFromSseData(String sseData) {
        try {
            // "data: " 접두사 제거
            String json = sseData;
            if (json.startsWith("data: ")) {
                json = json.substring(6);
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode candidates = root.path("candidates");

            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode content = candidates.get(0).path("content");
                JsonNode parts = content.path("parts");

                if (parts.isArray() && !parts.isEmpty()) {
                    return parts.get(0).path("text").asText("");
                }
            }
            return "";
        } catch (Exception e) {
            log.debug("SSE 데이터 파싱 스킵: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 텍스트 생성 (동기)
     */
    public String generateContent(String systemPrompt, String userPrompt) {
        return generateContentStream(systemPrompt, userPrompt)
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block();
    }

    /**
     * HTTP 에러 응답 처리
     */
    private Mono<Throwable> handleErrorResponse(org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.bodyToMono(String.class)
                .flatMap(body -> {
                    int statusCode = response.statusCode().value();
                    String errorMessage = parseErrorMessage(body);
                    
                    log.warn("Gemini API 에러 [{}]: {}", statusCode, errorMessage);

                    GeminiException exception = switch (statusCode) {
                        case 429 -> GeminiException.rateLimitExceeded(errorMessage);
                        case 401, 403 -> GeminiException.unauthorized(errorMessage);
                        case 400 -> GeminiException.badRequest(errorMessage);
                        default -> GeminiException.serverError(errorMessage);
                    };

                    return Mono.error(exception);
                });
    }

    /**
     * 에러 메시지 파싱
     */
    private String parseErrorMessage(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root.path("error");
            if (!error.isMissingNode()) {
                String message = error.path("message").asText("");
                String status = error.path("status").asText("");
                return String.format("%s (%s)", message, status);
            }
            return responseBody;
        } catch (Exception e) {
            return responseBody;
        }
    }

    /**
     * 재시도 스펙 생성 (Exponential Backoff)
     */
    private Retry createRetrySpec(String operationName) {
        return Retry.backoff(MAX_RETRIES, INITIAL_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .filter(throwable -> {
                    // Rate Limit 또는 서버 에러만 재시도
                    if (throwable instanceof GeminiException ge) {
                        return ge.isRetryable();
                    }
                    if (throwable instanceof WebClientResponseException wcre) {
                        int status = wcre.getStatusCode().value();
                        return status == 429 || status >= 500;
                    }
                    return false;
                })
                .doBeforeRetry(signal -> {
                    log.warn("🔄 {} 재시도 ({}/{}): {}", 
                            operationName,
                            signal.totalRetries() + 1, 
                            MAX_RETRIES,
                            signal.failure().getMessage());
                })
                .onRetryExhaustedThrow((spec, signal) -> {
                    log.error("❌ {} 최대 재시도 횟수 초과", operationName);
                    Throwable failure = signal.failure();
                    if (failure instanceof GeminiException) {
                        return (GeminiException) failure;
                    }
                    return GeminiException.serverError("최대 재시도 횟수 초과: " + failure.getMessage());
                });
    }

    /**
     * API 키 설정 여부 검증
     */
    private void validateApiKeyConfigured() {
        if (!StringUtils.hasText(apiKey)) {
            throw GeminiException.unauthorized("GOOGLE_API_KEY가 설정되지 않았습니다. 환경 변수를 확인해주세요.");
        }
    }
}

