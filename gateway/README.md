# DevBrain Gateway (Go)

Backend 서비스 앞단의 API Gateway입니다.

## 주요 기능

### 1. Reverse Proxy
- `/api/*` 요청을 Backend(Java)로 라우팅
- `/swagger-ui/*`, `/api-docs` 프록시 지원

### 2. 시맨틱 캐시 (Redis)
- 동일한 질문에 대해 캐시된 응답 반환
- 쿼리 정규화 (소문자, 공백 정리) 후 MD5 해시로 키 생성
- TTL 기반 캐시 만료

### 3. Rate Limiting
- IP 기반 Token Bucket 알고리즘
- 초당 요청 수 및 버스트 제한

### 4. SSE 스트리밍 프록시
- Backend의 SSE 응답을 클라이언트로 실시간 전달
- 스트리밍 응답도 캐시에 저장

## 디렉토리 구조

```
gateway/
├── cmd/
│   └── server/
│       └── main.go          # 진입점
├── internal/
│   ├── cache/
│   │   └── redis.go         # Redis 클라이언트
│   ├── config/
│   │   └── config.go        # 설정 로드
│   ├── handler/
│   │   └── proxy.go         # 프록시 핸들러
│   └── middleware/
│       ├── logging.go       # 로깅/CORS 미들웨어
│       └── ratelimiter.go   # Rate Limiter
├── go.mod
├── go.sum
└── README.md
```

## 환경 변수

| 변수 | 설명 | 기본값 |
|------|------|--------|
| `GATEWAY_PORT` | Gateway 포트 | 8080 |
| `BACKEND_URL` | Backend 서비스 URL | http://localhost:8081 |
| `REDIS_HOST` | Redis 호스트 | localhost |
| `REDIS_PORT` | Redis 포트 | 6379 |
| `REDIS_PASSWORD` | Redis 비밀번호 | (없음) |
| `RATE_LIMIT` | 초당 요청 수 | 10 |
| `RATE_BURST` | 버스트 허용량 | 20 |
| `CACHE_ENABLED` | 캐시 활성화 | true |
| `CACHE_TTL` | 캐시 TTL (초) | 3600 |

## 실행 방법

```bash
# 의존성 설치
cd gateway
go mod tidy

# 실행
go run cmd/server/main.go

# 또는 빌드 후 실행
go build -o bin/gateway cmd/server/main.go
./bin/gateway
```

## API 엔드포인트

| 엔드포인트 | 설명 |
|-----------|------|
| `GET /health` | Gateway 헬스체크 |
| `GET /api/chat/stream?q=질문` | SSE 스트리밍 채팅 (캐시 적용) |
| `POST /api/chat` | 동기 채팅 (캐시 적용) |
| `POST /api/search` | 하이브리드 검색 (프록시) |
| `GET /swagger-ui/*` | Swagger UI (프록시) |

## 캐시 동작

1. **캐시 키 생성**: 쿼리 정규화 → MD5 해시 → `chat:{hash}`
2. **캐시 히트**: Redis에서 응답 조회 → 즉시 반환
3. **캐시 미스**: Backend 호출 → 응답 캐시 저장 → 클라이언트 반환

### 헤더
- `X-Cache: HIT` - 캐시에서 응답
- `X-Cache: MISS` - Backend에서 응답



