package com.devbrain.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;

import com.devbrain.service.ChatService;
import com.devbrain.service.SearchResult;
import com.devbrain.service.SearchService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 채팅 API 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")  // 개발용, 운영에서는 제한 필요
@Tag(name = "Chat", description = "RAG 기반 채팅 및 검색 API")
public class ChatController {

    private final ChatService chatService;
    private final SearchService searchService;

    /**
     * 채팅 API (SSE 스트리밍)
     * GET /api/chat/stream?q=질문
     */
    @Operation(summary = "채팅 (SSE 스트리밍)", description = "RAG 기반 채팅 응답을 SSE로 스트리밍합니다")
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @Parameter(description = "질문", example = "TypeScript 프로젝트 구조 알려줘")
            @RequestParam("q") String query) {
        log.info("SSE 채팅 요청: {}", query);

        return chatService.chat(query)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build())
                .concatWith(Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("[DONE]")
                                .build()
                ));
    }

    /**
     * 채팅 API (동기)
     * POST /api/chat
     */
    @Operation(summary = "채팅 (동기)", description = "RAG 기반 채팅 응답을 동기로 반환합니다")
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        log.info("동기 채팅 요청: {}", request.query());

        String response = chatService.chatSync(request.query());

        return Map.of(
                "query", request.query(),
                "response", response
        );
    }

    /**
     * 검색 API (디버그용)
     * POST /api/search
     */
    @Operation(summary = "하이브리드 검색", description = "벡터 + 키워드 하이브리드 검색을 수행합니다")
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody SearchRequest request) {
        log.info("검색 요청: {}", request.query());

        List<SearchResult> results = searchService.hybridSearch(request.query());

        return Map.of(
                "query", request.query(),
                "count", results.size(),
                "results", results.stream().map(r -> Map.of(
                        "chunkId", r.getChunkId().toString(),
                        "score", r.getScore(),
                        "filePath", r.getFilePath() != null ? r.getFilePath() : "",
                        "functionName", r.getFunctionName() != null ? r.getFunctionName() : "",
                        "content", r.getContent().length() > 300
                                ? r.getContent().substring(0, 300) + "..."
                                : r.getContent()
                )).toList()
        );
    }

    /**
     * 헬스체크
     */
    @Operation(summary = "헬스체크", description = "서비스 상태를 확인합니다")
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "devbrain-backend");
    }

    // DTO 레코드
    record ChatRequest(String query) {}
    record SearchRequest(String query) {}
}

