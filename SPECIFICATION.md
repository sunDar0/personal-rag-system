# [기술 명세서] My Dev Brain: 사내/개인 지식 RAG 시스템

**버전:** 1.0.0
**대상 스택:** Java (Spring Boot), Go (Gateway), TypeScript (데이터 수집), PostgreSQL (pgvector)

---

## 1. 시스템 구성 및 데이터 흐름

### 1.1 전체 구조도

사용자의 질문이 어떤 경로로 처리되는지 정의합니다.

1. **사용자 (User):** 웹 UI를 통해 질문 전송.
2. **게이트웨이 (Go):** 모든 요청의 입구. 보안, 캐시, 트래픽 제어 담당.
3. **백엔드 (Java):** 실제 비즈니스 로직. DB 검색 및 AI 모델(Gemini) 통신.
4. **데이터베이스 (PostgreSQL):** 원본 텍스트와 벡터 데이터 저장.
5. **수집기 (TypeScript):** GitHub/Notion 데이터를 주기적으로 가져와 DB에 적재.

---

## 2. 데이터베이스 스키마 설계 (PostgreSQL)

`pgvector` 확장 모듈을 사용하여 구현할 테이블 구조입니다.

```sql
-- 1. 벡터 확장 모듈 활성화
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 원본 문서 테이블 (메타데이터 관리)
CREATE TABLE documents (
    id BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(50) NOT NULL, -- 예: 'GITHUB', 'NOTION'
    source_url TEXT NOT NULL UNIQUE,  -- 문서 식별자 (URL 또는 파일경로)
    title TEXT,                       -- 문서 제목 또는 파일명
    content_hash VARCHAR(64) NOT NULL,-- 변경 감지용 MD5 해시값
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 3. 벡터 조각 테이블 (실제 검색 대상)
CREATE TABLE document_chunks (
    id BIGUUID DEFAULT gen_random_uuid() PRIMARY KEY,
    document_id BIGINT REFERENCES documents(id) ON DELETE CASCADE,
    content TEXT NOT NULL,             -- 분할된 텍스트 본문
    metadata JSONB,                    -- 추가 정보 (페이지 번호, 함수명 등)
    embedding VECTOR(768),             -- Gemini 임베딩 차원 (768)
    
    -- 키워드 검색을 위한 TSVector (영어/한국어 형태소 분석)
    fts_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
);

-- 4. 인덱스 설정 (성능 최적화)
-- 벡터 검색 속도를 위한 HNSW 인덱스
CREATE INDEX ON document_chunks USING hnsw (embedding vector_cosine_ops);
-- 메타데이터 필터링을 위한 GIN 인덱스
CREATE INDEX ON document_chunks USING gin (metadata);

```

---

## 3. [Go] 지능형 게이트웨이 (Proxy) 상세 명세

단순 전달이 아닌 **비용 절감**과 **안정성**을 위한 계층입니다.

### 3.1 핵심 기능 요구사항

1. **의미 기반 캐시 (Semantic Cache):**
* Redis를 사용합니다.
* 사용자 질문이 들어오면 기존에 저장된 질문들과 벡터 유사도를 비교합니다.
* 유사도가 95% 이상이면 LLM 호출 없이 저장된 답변을 반환합니다.


2. **요청 제한 (Rate Limiting):**
* IP별로 분당 요청 횟수를 제한하여 과도한 트래픽을 차단합니다.


3. **스트리밍 중계:**
* Java 서버에서 오는 SSE(Server-Sent Events) 응답을 끊김 없이 클라이언트에게 전달합니다.



### 3.2 로직 의사 코드 (Pseudo Code)

```go
func HandleChatRequest(ctx) {
    // 1. 과부하 방지 (Rate Limit 확인)
    if !AllowRequest(ctx.ClientIP) {
        return Error(429, "요청이 너무 많습니다")
    }

    // 2. 캐시 확인 (Redis)
    cachedAnswer := SemanticCache.Find(ctx.Query)
    if cachedAnswer != nil {
        return Response(cachedAnswer) // 비용 0원
    }

    // 3. 백엔드로 요청 전달 (Reverse Proxy)
    // 응답을 스트리밍 모드로 유지하며 전달
    return Proxy.StreamTo("http://java-backend", ctx.Request)
}

```

---

## 4. [Java] RAG 백엔드 상세 명세 (Spring Boot)

**하이브리드 검색(Hybrid Search)** 알고리즘이 핵심입니다. 벡터 검색의 단점을 키워드 검색으로 보완합니다.

### 4.1 하이브리드 검색 쿼리 로직

벡터 유사도와 키워드 일치도를 결합하여 최적의 문서를 찾습니다.

* **입력:** 사용자 질문 텍스트, 질문의 벡터값
* **로직:**
1. **벡터 검색:** 임베딩 간 거리가 가까운 상위 20개 추출.
2. **키워드 검색:** 질문에 포함된 단어가 들어있는 문서 상위 20개 추출.
3. **점수 병합 (RRF 알고리즘 등):**
* `최종 점수 = (벡터 점수 * 0.7) + (키워드 점수 * 0.3)`


4. **최종 결과:** 점수가 높은 상위 5개 문서를 선택하여 Gemini에게 전달.



### 4.2 시스템 프롬프트 (System Prompt)

AI에게 역할을 부여하는 명령어입니다.

> "당신은 12년 차 백엔드 개발자의 기술 도우미입니다. 아래 제공된 [Context] 내용을 기반으로 질문에 답하세요. [Context]에 없는 내용은 지어내지 말고 모른다고 답하세요. 답변에는 코드 예시를 적극적으로 포함하세요."

---

## 5. [TypeScript] 데이터 수집기 상세 명세

소스 코드를 분석하여 DB에 넣는 작업입니다.

### 5.1 코드 분할 전략 (Chunking)

단순히 글자 수로 자르면 코드가 중간에 잘려 문맥이 파괴됩니다.

* **전략:** 프로그래밍 언어별 구분자(Separator)를 사용합니다.
* **Java/Go:** 클래스(`class`), 메서드(`func`, `void`), 중괄호(`}`) 기준으로 분할.
* **메타데이터 주입:** 잘라진 코드 조각의 상단에 반드시 **'파일 경로'**와 **'함수명'**을 주석으로 달아서 DB에 저장합니다. (검색 정확도 향상 필수 요소)

### 5.2 증분 동기화 (Incremental Sync)

매번 모든 데이터를 지우고 다시 넣지 않습니다.

1. GitHub API로 파일의 최신 해시(SHA)를 조회합니다.
2. DB에 저장된 해시와 비교합니다.
3. 변경된 파일만 삭제 후 다시 임베딩하여 저장합니다.

---

## 6. 환경 변수 설정 (.env)

프로젝트 루트에 생성할 설정 파일 예시입니다.

```ini
# 데이터베이스 설정
DB_HOST=localhost
DB_PORT=5432
DB_USER=postgres
DB_PASSWORD=secret
DB_NAME=dev_brain

# AI 모델 키 (Google AI Studio)
GOOGLE_API_KEY=AIzaSy...

# GitHub 연동 (Private Repo 접근용)
GITHUB_TOKEN=ghp_...

# 캐시 서버
REDIS_HOST=localhost

```

---

### [작업 지시 가이드]

이제 Cursor에게 다음과 같이 지시하시면 됩니다.

1. 위 내용을 복사해서 **`SPECIFICATION.md`** 파일로 저장합니다.
2. **Cursor 채팅창(Cmd+L)**에 파일을 입력으로 넣고 첫 번째 명령을 내립니다:

> **"@SPECIFICATION.md 문서의 '2. 데이터베이스 스키마 설계' 항목을 참고하여, `init.sql` 파일과 이를 실행할 `docker-compose.yml` 파일을 작성해 줘."**

이 명세서라면 혼동 없이 깔끔하게 코드 작성이 가능할 것입니다. **어느 부분부터 코드 생성을 시작할까요?**