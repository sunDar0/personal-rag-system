-- ============================================
-- My Dev Brain - PostgreSQL 초기화 스크립트
-- ============================================
-- 이 스크립트는 Docker 컨테이너 최초 실행 시 자동으로 실행됩니다.

-- 1. 벡터 확장 모듈 활성화
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. UUID 생성 함수 활성화
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================
-- 📄 원본 문서 테이블
-- ============================================
CREATE TABLE documents (
    id BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(50) NOT NULL,           -- 'GITHUB', 'NOTION'
    source_url TEXT NOT NULL UNIQUE,            -- 문서 식별자 (URL 또는 파일경로)
    title TEXT,                                  -- 문서 제목 또는 파일명
    content_hash VARCHAR(64) NOT NULL,          -- 변경 감지용 MD5 해시값
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- source_type 인덱스 (필터링용)
CREATE INDEX idx_documents_source_type ON documents(source_type);

-- ============================================
-- 📦 벡터 조각 테이블 (실제 검색 대상)
-- ============================================
CREATE TABLE document_chunks (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,               -- 문서 내 청크 순서
    content TEXT NOT NULL,                      -- 분할된 텍스트 본문
    metadata JSONB DEFAULT '{}',                -- 추가 정보 (파일경로, 함수명, 언어 등)
    embedding VECTOR(768),                      -- Gemini 임베딩 차원 (768)
    
    -- Full-Text Search를 위한 TSVector
    -- 영어 형태소 분석 (한국어는 별도 설정 필요)
    fts_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(metadata->>'title', '')), 'A') ||
        setweight(to_tsvector('english', coalesce(metadata->>'functionName', '')), 'B') ||
        setweight(to_tsvector('english', content), 'C')
    ) STORED,
    
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- ============================================
-- 🔍 인덱스 설정 (성능 최적화)
-- ============================================

-- 벡터 검색용 HNSW 인덱스 (코사인 유사도)
-- m: 각 노드의 최대 연결 수 (높을수록 정확도↑, 메모리↑)
-- ef_construction: 인덱스 구축 시 탐색 범위 (높을수록 정확도↑, 구축시간↑)
CREATE INDEX idx_chunks_embedding ON document_chunks 
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Full-Text Search용 GIN 인덱스
CREATE INDEX idx_chunks_fts ON document_chunks USING GIN (fts_vector);

-- 메타데이터 필터링용 GIN 인덱스
CREATE INDEX idx_chunks_metadata ON document_chunks USING GIN (metadata);

-- document_id 참조용 인덱스
CREATE INDEX idx_chunks_document_id ON document_chunks(document_id);

-- ============================================
-- 🔄 updated_at 자동 갱신 트리거
-- ============================================
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_documents_updated_at
    BEFORE UPDATE ON documents
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- ============================================
-- 🔧 유틸리티 함수
-- ============================================

-- 벡터 유사도 검색 함수
CREATE OR REPLACE FUNCTION search_by_vector(
    query_embedding VECTOR(768),
    match_count INTEGER DEFAULT 10,
    similarity_threshold FLOAT DEFAULT 0.5
)
RETURNS TABLE (
    chunk_id UUID,
    document_id BIGINT,
    content TEXT,
    metadata JSONB,
    similarity FLOAT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        dc.id AS chunk_id,
        dc.document_id,
        dc.content,
        dc.metadata,
        1 - (dc.embedding <=> query_embedding) AS similarity
    FROM document_chunks dc
    WHERE dc.embedding IS NOT NULL
      AND 1 - (dc.embedding <=> query_embedding) > similarity_threshold
    ORDER BY dc.embedding <=> query_embedding
    LIMIT match_count;
END;
$$ LANGUAGE plpgsql;

-- 키워드 검색 함수
CREATE OR REPLACE FUNCTION search_by_keyword(
    query_text TEXT,
    match_count INTEGER DEFAULT 10
)
RETURNS TABLE (
    chunk_id UUID,
    document_id BIGINT,
    content TEXT,
    metadata JSONB,
    rank FLOAT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        dc.id AS chunk_id,
        dc.document_id,
        dc.content,
        dc.metadata,
        ts_rank(dc.fts_vector, plainto_tsquery('english', query_text)) AS rank
    FROM document_chunks dc
    WHERE dc.fts_vector @@ plainto_tsquery('english', query_text)
    ORDER BY rank DESC
    LIMIT match_count;
END;
$$ LANGUAGE plpgsql;

-- ============================================
-- ✅ 초기화 완료 메시지
-- ============================================
DO $$
BEGIN
    RAISE NOTICE '✅ My Dev Brain 데이터베이스 초기화 완료!';
    RAISE NOTICE '📊 테이블: documents, document_chunks';
    RAISE NOTICE '🔍 인덱스: HNSW(vector), GIN(fts), GIN(metadata)';
    RAISE NOTICE '🔧 함수: search_by_vector, search_by_keyword';
END $$;

