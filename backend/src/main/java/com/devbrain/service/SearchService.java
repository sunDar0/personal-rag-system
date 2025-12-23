package com.devbrain.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.devbrain.domain.document.DocumentChunkRepository;
import com.devbrain.infrastructure.gemini.GeminiClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 하이브리드 검색 서비스
 * 벡터 검색 + 키워드 검색을 결합하여 최적의 결과 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final DocumentChunkRepository chunkRepository;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    @Value("${search.vector-weight}")
    private double vectorWeight;

    @Value("${search.keyword-weight}")
    private double keywordWeight;

    @Value("${search.top-k}")
    private int topK;

    /**
     * 하이브리드 검색 수행
     * 1. 쿼리 임베딩 생성
     * 2. 벡터 검색 (코사인 유사도)
     * 3. 키워드 검색 (Full-Text Search)
     * 4. RRF 알고리즘으로 점수 병합
     */
    public List<SearchResult> hybridSearch(String query) {
        log.info("하이브리드 검색 시작: {}", query);

        // 1. 쿼리 임베딩 생성
        List<Double> queryEmbedding = geminiClient.embedText(query);
        String embeddingStr = "[" + queryEmbedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",")) + "]";

        // 2. 벡터 검색
        List<SearchResult> vectorResults = searchByVector(embeddingStr, topK * 2);
        log.debug("벡터 검색 결과: {}개", vectorResults.size());

        // 3. 키워드 검색
        List<SearchResult> keywordResults = searchByKeyword(query, topK * 2);
        log.debug("키워드 검색 결과: {}개", keywordResults.size());

        // 4. RRF 점수 병합
        List<SearchResult> merged = mergeWithRRF(vectorResults, keywordResults, topK);
        log.info("최종 검색 결과: {}개", merged.size());

        return merged;
    }

    /**
     * 벡터 유사도 검색
     */
    private List<SearchResult> searchByVector(String embedding, int limit) {
        List<Object[]> results = chunkRepository.findByVectorSimilarity(embedding, limit);
        return mapToSearchResults(results);
    }

    /**
     * 키워드 검색
     */
    private List<SearchResult> searchByKeyword(String query, int limit) {
        List<Object[]> results = chunkRepository.findByKeywordSearch(query, limit);
        return mapToSearchResults(results);
    }

    /**
     * Native Query 결과를 SearchResult로 변환
     */
    @SuppressWarnings("unchecked")
    private List<SearchResult> mapToSearchResults(List<Object[]> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        return rows.stream()
                .filter(row -> row != null && row.length >= 7)
                .map(row -> {
                    try {
                        // 필수 필드 null 체크
                        UUID chunkId = row[0] != null ? (UUID) row[0] : null;
                        if (chunkId == null) {
                            log.warn("검색 결과에 chunkId가 null입니다");
                            return null;
                        }

                        Long documentId = row[1] != null ? ((Number) row[1]).longValue() : null;
                        String content = row[3] != null ? (String) row[3] : "";
                        Double score = row[6] != null ? ((Number) row[6]).doubleValue() : 0.0;

                        // 메타데이터 파싱
                        Map<String, Object> metadata = null;
                        Object metadataObj = row[4];

                        if (metadataObj != null) {
                            try {
                                if (metadataObj instanceof String) {
                                    metadata = objectMapper.readValue((String) metadataObj,
                                            new TypeReference<Map<String, Object>>() {});
                                } else if (metadataObj instanceof Map) {
                                    metadata = (Map<String, Object>) metadataObj;
                                }
                            } catch (Exception e) {
                                log.warn("메타데이터 파싱 실패: {}", e.getMessage());
                            }
                        }

                        return SearchResult.builder()
                                .chunkId(chunkId)
                                .documentId(documentId)
                                .content(content)
                                .metadata(metadata)
                                .score(score)
                                .build();
                    } catch (Exception e) {
                        log.error("검색 결과 변환 실패: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(result -> result != null)
                .collect(Collectors.toList());
    }

    /**
     * RRF (Reciprocal Rank Fusion) 알고리즘으로 점수 병합
     * 최종 점수 = Σ 1 / (k + rank)
     * k = 60 (상수)
     */
    private List<SearchResult> mergeWithRRF(
            List<SearchResult> vectorResults,
            List<SearchResult> keywordResults,
            int topK) {

        final int k = 60;
        Map<UUID, Double> rrfScores = new HashMap<>();
        Map<UUID, SearchResult> resultMap = new HashMap<>();

        // 벡터 검색 결과 점수 계산
        for (int i = 0; i < vectorResults.size(); i++) {
            SearchResult result = vectorResults.get(i);
            UUID chunkId = result.getChunkId();
            
            // chunkId가 null인 경우 건너뜀
            if (chunkId == null) {
                log.warn("벡터 검색 결과에 chunkId가 null인 항목 발견, 스킵");
                continue;
            }
            
            double score = vectorWeight / (k + i + 1);
            rrfScores.merge(chunkId, score, (oldVal, newVal) -> 
                    (oldVal != null ? oldVal : 0.0) + (newVal != null ? newVal : 0.0));
            resultMap.put(chunkId, result);
        }

        // 키워드 검색 결과 점수 계산
        for (int i = 0; i < keywordResults.size(); i++) {
            SearchResult result = keywordResults.get(i);
            UUID chunkId = result.getChunkId();
            
            // chunkId가 null인 경우 건너뜀
            if (chunkId == null) {
                log.warn("키워드 검색 결과에 chunkId가 null인 항목 발견, 스킵");
                continue;
            }
            
            double score = keywordWeight / (k + i + 1);
            rrfScores.merge(chunkId, score, (oldVal, newVal) -> 
                    (oldVal != null ? oldVal : 0.0) + (newVal != null ? newVal : 0.0));
            resultMap.putIfAbsent(chunkId, result);
        }

        // 점수 기준 정렬 후 상위 K개 반환
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    SearchResult original = resultMap.get(entry.getKey());
                    if (original == null) {
                        log.warn("resultMap에서 chunkId {}에 해당하는 결과를 찾을 수 없음", entry.getKey());
                        return null;
                    }
                    return SearchResult.builder()
                            .chunkId(original.getChunkId())
                            .documentId(original.getDocumentId())
                            .content(original.getContent())
                            .metadata(original.getMetadata())
                            .score(entry.getValue())
                            .build();
                })
                .filter(result -> result != null)
                .collect(Collectors.toList());
    }
}

