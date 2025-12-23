package com.devbrain.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.devbrain.infrastructure.gemini.GeminiClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * RAG 채팅 서비스
 * 하이브리드 검색 결과를 컨텍스트로 사용하여 LLM 응답 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final SearchService searchService;
    private final GeminiClient geminiClient;

    /**
     * 시스템 프롬프트
     */
    private static final String SYSTEM_PROMPT = """
        당신은 20년 차 백엔드 개발자의 기술 도우미입니다.
        
        ## 규칙
        1. 아래 제공된 [Context] 내용을 기반으로만 답변하세요.
        2. [Context]에 없는 내용은 지어내지 말고 "해당 정보를 찾을 수 없습니다"라고 답하세요.
        3. 답변에는 코드 예시를 적극적으로 포함하세요.
        4. 코드 출처(파일 경로)를 명시하세요.
        5. 한국어로 답변하세요.
        """;

    /**
     * RAG 기반 채팅 응답 생성 (스트리밍)
     */
    public Flux<String> chat(String query) {
        log.info("채팅 요청: {}", query);

        // 1. 하이브리드 검색으로 관련 문서 검색
        List<SearchResult> searchResults = searchService.hybridSearch(query);

        if (searchResults.isEmpty()) {
            log.warn("검색 결과 없음");
            return Flux.just("관련 문서를 찾을 수 없습니다. 다른 질문을 시도해 주세요.");
        }

        // 2. 컨텍스트 구성
        String context = buildContext(searchResults);
        String userPrompt = buildUserPrompt(context, query);

        log.debug("컨텍스트 크기: {} 문자", context.length());

        // 3. LLM 응답 생성 (스트리밍)
        return geminiClient.generateContentStream(SYSTEM_PROMPT, userPrompt);
    }

    /**
     * 검색 결과로 컨텍스트 구성
     */
    private String buildContext(List<SearchResult> results) {
        return results.stream()
                .map(result -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("---\n");
                    if (result.getFilePath() != null) {
                        sb.append("파일: ").append(result.getFilePath()).append("\n");
                    }
                    if (result.getFunctionName() != null) {
                        sb.append("함수: ").append(result.getFunctionName()).append("\n");
                    }
                    sb.append("\n").append(result.getContent()).append("\n");
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    /**
     * 사용자 프롬프트 구성
     */
    private String buildUserPrompt(String context, String query) {
        return String.format("""
                ## Context
                %s
                
                ## 질문
                %s
                """, context, query);
    }

    /**
     * RAG 기반 채팅 응답 생성 (동기)
     */
    public String chatSync(String query) {
        return chat(query)
                .collectList()
                .map(chunks -> String.join("", chunks))
                .block();
    }
}

