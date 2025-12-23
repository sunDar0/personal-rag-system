# ☕ My Dev Brain - Backend

Spring Boot로 구현된 RAG(Retrieval-Augmented Generation) 백엔드입니다.

## 🚀 주요 기능

- **하이브리드 검색**: 벡터 유사도 + 키워드 검색 결합
- **RRF 알고리즘**: 검색 결과 점수 병합
- **Gemini 연동**: 임베딩 생성 + 텍스트 생성
- **SSE 스트리밍**: 실시간 응답 전송

## 📁 프로젝트 구조

```
backend/
├── src/main/java/com/devbrain/
│   ├── DevBrainApplication.java    # 메인 클래스
│   ├── config/                     # 설정
│   ├── domain/
│   │   └── document/               # 문서 엔티티
│   ├── service/
│   │   ├── SearchService.java      # 하이브리드 검색
│   │   └── ChatService.java        # RAG 채팅
│   ├── controller/
│   │   └── ChatController.java     # REST API
│   └── infrastructure/
│       └── gemini/
│           └── GeminiClient.java   # Gemini API 클라이언트
├── src/main/resources/
│   └── application.yml
├── build.gradle.kts
└── README.md
```

## 🛠️ 실행 방법

### 1. 환경 변수 설정

프로젝트 루트의 `.env` 파일에 다음 값들이 필요합니다:

```env
DB_HOST=localhost
DB_PORT=5432
DB_USER=postgres
DB_PASSWORD=your_password
DB_NAME=dev_brain
GOOGLE_API_KEY=your_gemini_api_key
```

### 2. 빌드 및 실행

```bash
cd backend

# Gradle Wrapper 생성 (최초 1회)
gradle wrapper

# 실행
./gradlew bootRun

# 또는 환경 변수 직접 지정
DB_PASSWORD=secret GOOGLE_API_KEY=xxx ./gradlew bootRun
```

## 📡 API 엔드포인트

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/health` | 헬스체크 |
| GET | `/api/chat/stream?q=질문` | SSE 스트리밍 채팅 |
| POST | `/api/chat` | 동기 채팅 |
| POST | `/api/search` | 하이브리드 검색 (디버그) |

### 예시

```bash
# 헬스체크
curl http://localhost:8081/api/health

# SSE 스트리밍 채팅
curl -N "http://localhost:8081/api/chat/stream?q=Spring%20Boot%20JWT%20인증"

# 동기 채팅
curl -X POST http://localhost:8081/api/chat \
  -H "Content-Type: application/json" \
  -d '{"query": "Spring Boot에서 JWT 인증 구현 방법"}'

# 검색 (디버그)
curl -X POST http://localhost:8081/api/search \
  -H "Content-Type: application/json" \
  -d '{"query": "JWT 인증"}'
```

## 🔍 하이브리드 검색 알고리즘

```
최종 점수 = (벡터 점수 × 0.7) + (키워드 점수 × 0.3)
```

### RRF (Reciprocal Rank Fusion)

```
점수 = Σ 1 / (k + rank)
k = 60 (상수)
```

## ⚙️ 설정

`application.yml`에서 조정 가능:

```yaml
search:
  vector-weight: 0.7    # 벡터 검색 가중치
  keyword-weight: 0.3   # 키워드 검색 가중치
  top-k: 5              # 반환할 결과 수

gemini:
  model: gemini-2.0-flash  # LLM 모델
  embedding-model: text-embedding-004  # 임베딩 모델
```

