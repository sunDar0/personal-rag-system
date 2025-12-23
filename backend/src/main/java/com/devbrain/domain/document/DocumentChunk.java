package com.devbrain.domain.document;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문서 청크 엔티티
 * 분할된 텍스트 + 벡터 임베딩
 */
@Entity
@Table(name = "document_chunks")
@Getter
@NoArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    // 임베딩은 Native Query로 처리 (pgvector)

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    /**
     * 메타데이터에서 파일 경로 추출
     */
    public String getFilePath() {
        if (metadata == null) return null;
        return (String) metadata.get("filePath");
    }

    /**
     * 메타데이터에서 함수명 추출
     */
    public String getFunctionName() {
        if (metadata == null) return null;
        return (String) metadata.get("functionName");
    }

    /**
     * 메타데이터에서 언어 추출
     */
    public String getLanguage() {
        if (metadata == null) return null;
        return (String) metadata.get("language");
    }
}

