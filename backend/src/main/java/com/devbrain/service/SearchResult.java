package com.devbrain.service;

import java.util.Map;
import java.util.UUID;

import lombok.Builder;
import lombok.Getter;

/**
 * 검색 결과 DTO
 */
@Getter
@Builder
public class SearchResult {
    private UUID chunkId;
    private Long documentId;
    private String content;
    private Map<String, Object> metadata;
    private double score;  // 벡터 또는 키워드 점수

    /**
     * 파일 경로 추출
     */
    public String getFilePath() {
        if (metadata == null) return null;
        return (String) metadata.get("filePath");
    }

    /**
     * 함수명 추출
     */
    public String getFunctionName() {
        if (metadata == null) return null;
        return (String) metadata.get("functionName");
    }
}

