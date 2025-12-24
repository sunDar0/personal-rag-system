# 🧠 My Dev Brain

> **내 코드베이스에서 AI가 답변을 찾아주는 개발자용 지식 검색 엔진**

코드나 문서를 수집하고, 질문하면 관련 내용을 찾아 AI가 답변해주는 개인용 RAG(Retrieval-Augmented Generation) 시스템입니다.

## ✨ 주요 기능

- 🔍 **하이브리드 검색**: 벡터 유사도 + 키워드 검색을 결합한 정확한 코드 검색
- 💬 **AI 채팅**: Google Gemini 기반 실시간 스트리밍 응답
- 📥 **자동 수집**: GitHub 레포지토리에서 코드 자동 수집 및 청킹
- ⚡ **시맨틱 캐시**: 유사한 질문에 대한 빠른 응답 (Redis)
- 🛡️ **Rate Limiting**: IP 기반 요청 제한

## 🏗️ 아키텍처

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│   Gateway   │────▶│   Backend   │
│             │     │    (Go)     │     │   (Java)    │
└─────────────┘     └──────┬──────┘     └──────┬──────┘
                           │                    │
                    ┌──────▼──────┐      ┌──────▼──────┐
                    │    Redis    │      │ PostgreSQL  │
                    │   (Cache)   │      │ + pgvector  │
                    └─────────────┘      └──────▲──────┘
                                                │
                                         ┌──────┴──────┐
                                         │  Collector  │
                                         │(TypeScript) │
                                         └─────────────┘
```

## 🛠️ 기술 스택

| 계층 | 기술 | 역할 |
|------|------|------|
| **Gateway** | Go | Reverse Proxy, Rate Limiting, 시맨틱 캐시 |
| **Backend** | Java Spring Boot | RAG 로직, 하이브리드 검색, Gemini 연동 |
| **Collector** | TypeScript | GitHub 코드 수집, 청킹, 임베딩 생성 |
| **Database** | PostgreSQL + pgvector | 벡터 저장 및 유사도 검색 |
| **Cache** | Redis | 시맨틱 캐시, 세션 관리 |
| **AI** | Google Gemini | 임베딩 생성, 텍스트 생성 |

## 🚀 빠른 시작

### 1. 환경 변수 설정

```bash
cp .env.example .env
# .env 파일에 실제 값 입력
```

필수 환경 변수:
- `DB_PASSWORD`: PostgreSQL 비밀번호
- `GOOGLE_API_KEY`: [Google AI Studio](https://aistudio.google.com/app/apikey)에서 발급
- `GITHUB_TOKEN`: [GitHub Settings](https://github.com/settings/tokens)에서 발급

### 2. 인프라 실행

```bash
# PostgreSQL + Redis 시작
docker compose up -d postgres redis
```

### 3. 서비스 실행

```bash
# Terminal 1: Backend
cd backend && ./gradlew bootRun

# Terminal 2: Gateway
cd gateway && go run cmd/server/main.go

# Terminal 3: Collector (데이터 수집)
cd collector && pnpm install && pnpm sync
```

### 4. 테스트

```bash
# 헬스체크
curl http://localhost:8080/health

# AI 채팅 (SSE 스트리밍)
curl -N "http://localhost:8080/api/chat/stream?q=JWT%20인증%20방법"
```

## 📁 프로젝트 구조

```
my-dev-brain/
├── gateway/           # Go API Gateway
│   ├── cmd/server/    # 진입점
│   └── internal/      # 핸들러, 미들웨어, 캐시
├── backend/           # Java Spring Boot
│   └── src/main/java/com/devbrain/
│       ├── controller/    # REST API
│       ├── service/       # 검색, 채팅 로직
│       └── infrastructure/# Gemini 클라이언트
├── collector/         # TypeScript 수집기
│   └── src/
│       ├── sources/   # GitHub 클라이언트
│       ├── chunking/  # 코드 분할
│       └── embedding/ # Gemini 임베딩
├── infrastructure/    # Docker, DB 스크립트
│   └── postgres/init.sql
├── docker-compose.yml
└── .env.example
```

## 📡 API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| GET | `/health` | Gateway 헬스체크 |
| GET | `/api/health` | Backend 헬스체크 |
| GET | `/api/chat/stream?q=질문` | SSE 스트리밍 채팅 |
| POST | `/api/chat` | 동기 채팅 |
| POST | `/api/search` | 하이브리드 검색 (디버그) |

### 예시

```bash
# SSE 스트리밍 채팅
curl -N "http://localhost:8080/api/chat/stream?q=Spring에서%20JWT%20구현"

# 동기 채팅
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"query": "토큰 검증 방법"}'

# 검색
curl -X POST http://localhost:8080/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "인증 로직"}'
```

## ⚙️ 환경 변수

| 변수 | 필수 | 설명 |
|------|------|------|
| `DB_PASSWORD` | ✅ | PostgreSQL 비밀번호 |
| `GOOGLE_API_KEY` | ✅ | Gemini API 키 |
| `GITHUB_TOKEN` | ✅ | GitHub Personal Access Token |
| `GITHUB_REPOS` | ✅ | 동기화할 레포 (`owner/repo1,owner/repo2`) |
| `REDIS_PASSWORD` | - | Redis 비밀번호 (선택) |
| `RATE_LIMIT` | - | 초당 요청 수 (기본: 10) |
| `CACHE_TTL` | - | 캐시 TTL 초 (기본: 3600) |

## 🔧 개발 가이드

### 포트 규칙

| 서비스 | 포트 |
|--------|------|
| Gateway | 8080 |
| Backend | 8081 |
| PostgreSQL | 5432 |
| Redis | 6379 |

### Git 커밋 컨벤션

```
[서비스명] 타입: 설명

예시:
[backend] feat: 하이브리드 검색 구현
[gateway] fix: Rate Limiter 버그 수정
[collector] refactor: 청킹 로직 개선
```

## 📚 상세 문서

- [Gateway README](./gateway/README.md)
- [Backend README](./backend/README.md)
- [Collector README](./collector/README.md)
- [아키텍처 설계](./ARCHITECTURE.md)
- [기술 명세서](./SPECIFICATION.md)
