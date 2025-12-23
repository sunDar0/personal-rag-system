package com.devbrain.domain.document;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, UUID> {

    /**
     * 벡터 유사도 검색 (코사인 거리)
     * pgvector의 <=> 연산자 사용
     */
    @Query(value = """
        SELECT c.id, c.document_id, c.chunk_index, c.content, c.metadata, c.created_at,
               1 - (c.embedding <=> cast(:embedding as vector)) as similarity
        FROM document_chunks c
        WHERE c.embedding IS NOT NULL
        ORDER BY c.embedding <=> cast(:embedding as vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByVectorSimilarity(
            @Param("embedding") String embedding,
            @Param("limit") int limit
    );

    /**
     * 키워드 검색 (Full-Text Search)
     * PostgreSQL의 ts_rank 함수 사용
     */
    @Query(value = """
        SELECT c.id, c.document_id, c.chunk_index, c.content, c.metadata, c.created_at,
               ts_rank(c.fts_vector, plainto_tsquery('english', :query)) as rank
        FROM document_chunks c
        WHERE c.fts_vector @@ plainto_tsquery('english', :query)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByKeywordSearch(
            @Param("query") String query,
            @Param("limit") int limit
    );
}

