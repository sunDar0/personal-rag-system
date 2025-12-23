package com.devbrain.infrastructure.gemini;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * Google Gemini API 클라이언트
 */
@Slf4j
@Component
public class GeminiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model}")
    private String model;

    @Value("${gemini.embedding-model}")
    private String embeddingModel;

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    public GeminiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    /**
     * 텍스트 임베딩 생성
     */
    public List<Double> embedText(String text) {
        String url = String.format("/models/%s:embedContent?key=%s", embeddingModel, apiKey);

        Map<String, Object> requestBody = Map.of(
                "model", "models/" + embeddingModel,
                "content", Map.of(
                        "parts", List.of(Map.of("text", text))
                )
        );

        try {
            String response = webClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            JsonNode values = root.path("embedding").path("values");

            return objectMapper.convertValue(values,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class));
        } catch (Exception e) {
            log.error("임베딩 생성 실패: {}", e.getMessage());
            throw new RuntimeException("임베딩 생성 실패", e);
        }
    }

    /**
     * 텍스트 생성 (스트리밍)
     */
    public Flux<String> generateContentStream(String systemPrompt, String userPrompt) {
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
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(data -> !data.isEmpty())
                .map(this::extractTextFromSseData)
                .filter(text -> text != null && !text.isEmpty());
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
}

