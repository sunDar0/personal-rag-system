package handler

import (
	"bufio"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
	"time"

	"github.com/devbrain/gateway/internal/cache"
	"github.com/devbrain/gateway/internal/config"
)

// ProxyHandler는 Backend로 요청을 프록시하는 핸들러
type ProxyHandler struct {
	backendURL  *url.URL
	proxy       *httputil.ReverseProxy
	redisClient *cache.RedisClient
	config      *config.Config
}

// NewProxyHandler는 새로운 ProxyHandler 생성
func NewProxyHandler(backendURL string, redisClient *cache.RedisClient, cfg *config.Config) *ProxyHandler {
	target, err := url.Parse(backendURL)
	if err != nil {
		log.Fatalf("❌ Backend URL 파싱 실패: %v", err)
	}

	proxy := httputil.NewSingleHostReverseProxy(target)

	// 에러 핸들러 커스터마이징
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		log.Printf("❌ 프록시 에러: %v", err)
		http.Error(w, `{"error": "Backend Unavailable", "message": "백엔드 서버에 연결할 수 없습니다."}`, http.StatusBadGateway)
	}

	return &ProxyHandler{
		backendURL:  target,
		proxy:       proxy,
		redisClient: redisClient,
		config:      cfg,
	}
}

// ServeHTTP는 HTTP 요청 처리
func (h *ProxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	path := r.URL.Path

	// 라우팅
	switch {
	case path == "/health" || path == "/api/health":
		h.handleHealth(w, r)

	case path == "/api/chat/stream":
		h.handleChatStream(w, r)

	case path == "/api/chat" && r.Method == http.MethodPost:
		h.handleChatSync(w, r)

	case strings.HasPrefix(path, "/api/"):
		// 일반 API 요청은 그대로 프록시
		h.proxy.ServeHTTP(w, r)

	case strings.HasPrefix(path, "/swagger") || strings.HasPrefix(path, "/api-docs"):
		// Swagger UI도 프록시
		h.proxy.ServeHTTP(w, r)

	default:
		http.NotFound(w, r)
	}
}

// handleHealth는 헬스체크 엔드포인트
func (h *ProxyHandler) handleHealth(w http.ResponseWriter, _ *http.Request) {
	status := map[string]any{
		"status":  "ok",
		"service": "devbrain-gateway",
		"redis":   h.redisClient.IsConnected(),
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(status)
}

// handleChatSync는 동기 채팅 요청 처리 (캐시 적용)
func (h *ProxyHandler) handleChatSync(w http.ResponseWriter, r *http.Request) {
	// 요청 바디 읽기
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, `{"error": "Bad Request"}`, http.StatusBadRequest)
		return
	}
	r.Body = io.NopCloser(bytes.NewBuffer(body))

	// 쿼리 추출
	var req struct {
		Query string `json:"query"`
	}
	if err := json.Unmarshal(body, &req); err != nil || req.Query == "" {
		// 파싱 실패시 그냥 프록시
		r.Body = io.NopCloser(bytes.NewBuffer(body))
		h.proxy.ServeHTTP(w, r)
		return
	}

	// 캐시 확인
	if h.config.CacheEnabled && h.redisClient.IsConnected() {
		if cached, err := h.redisClient.Get(req.Query); err == nil && cached != nil {
			log.Printf("💾 캐시 히트: %s", req.Query[:min(30, len(req.Query))])
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("X-Cache", "HIT")
			
			response := map[string]any{
				"query":    req.Query,
				"response": cached.Response,
				"cached":   true,
			}
			json.NewEncoder(w).Encode(response)
			return
		}
	}

	// 캐시 미스: Backend로 프록시하고 응답 캡처
	log.Printf("🔄 캐시 미스: %s", req.Query[:min(30, len(req.Query))])
	
	// 응답 캡처를 위한 래퍼
	rec := &responseRecorder{
		ResponseWriter: w,
		body: &bytes.Buffer{},
	}

	r.Body = io.NopCloser(bytes.NewBuffer(body))
	h.proxy.ServeHTTP(rec, r)

	// 성공 응답이면 캐시에 저장
	if rec.statusCode == http.StatusOK && h.config.CacheEnabled && h.redisClient.IsConnected() {
		var resp struct {
			Response string `json:"response"`
		}
		if err := json.Unmarshal(rec.body.Bytes(), &resp); err == nil && resp.Response != "" {
			ttl := time.Duration(h.config.CacheTTL) * time.Second
			if err := h.redisClient.Set(req.Query, resp.Response, ttl); err != nil {
				log.Printf("⚠️ 캐시 저장 실패: %v", err)
			} else {
				log.Printf("💾 캐시 저장: %s", req.Query[:min(30, len(req.Query))])
			}
		}
	}
}

// handleChatStream는 SSE 스트리밍 채팅 요청 처리
func (h *ProxyHandler) handleChatStream(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query().Get("q")
	if query == "" {
		http.Error(w, `{"error": "Missing query parameter 'q'"}`, http.StatusBadRequest)
		return
	}

	// 캐시 확인 (스트리밍에서도 캐시된 응답이 있으면 사용)
	if h.config.CacheEnabled && h.redisClient.IsConnected() {
		if cached, err := h.redisClient.Get(query); err == nil && cached != nil {
			log.Printf("💾 캐시 히트 (SSE): %s", query[:min(30, len(query))])
			h.sendCachedSSE(w, cached.Response)
			return
		}
	}

	log.Printf("🔄 SSE 스트리밍 시작: %s", query[:min(30, len(query))])

	// Backend SSE 요청
	backendURL := fmt.Sprintf("%s/api/chat/stream?q=%s", h.backendURL.String(), url.QueryEscape(query))
	
	resp, err := http.Get(backendURL)
	if err != nil {
		log.Printf("❌ Backend 연결 실패: %v", err)
		http.Error(w, `{"error": "Backend Unavailable"}`, http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()

	// SSE 헤더 설정
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no")

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming not supported", http.StatusInternalServerError)
		return
	}

	// 응답 수집 (캐시용)
	var fullResponse strings.Builder

	// SSE 이벤트 프록시
	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := scanner.Text()
		
		// 클라이언트로 전달
		fmt.Fprintln(w, line)
		flusher.Flush()

		// 데이터 라인에서 응답 수집
		if strings.HasPrefix(line, "data:") {
			data := strings.TrimPrefix(line, "data:")
			data = strings.TrimSpace(data)
			if data != "[DONE]" {
				fullResponse.WriteString(data)
			}
		}
	}

	// 캐시에 저장
	if h.config.CacheEnabled && h.redisClient.IsConnected() && fullResponse.Len() > 0 {
		ttl := time.Duration(h.config.CacheTTL) * time.Second
		if err := h.redisClient.Set(query, fullResponse.String(), ttl); err != nil {
			log.Printf("⚠️ 캐시 저장 실패: %v", err)
		} else {
			log.Printf("💾 캐시 저장 (SSE): %s", query[:min(30, len(query))])
		}
	}
}

// sendCachedSSE는 캐시된 응답을 SSE 형식으로 전송
func (h *ProxyHandler) sendCachedSSE(w http.ResponseWriter, response string) {
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Cache", "HIT")

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "Streaming not supported", http.StatusInternalServerError)
		return
	}

	// 캐시된 응답을 청크로 나눠서 스트리밍 효과 유지
	chunkSize := 20
	for i := 0; i < len(response); i += chunkSize {
		end := min(i+chunkSize, len(response))
		chunk := response[i:end]

		fmt.Fprintf(w, "data:%s\n\n", chunk)
		flusher.Flush()
		time.Sleep(10 * time.Millisecond) // 자연스러운 스트리밍 효과
	}

	// 완료 이벤트
	fmt.Fprint(w, "event:done\ndata:[DONE]\n\n")
	flusher.Flush()
}

// responseRecorder는 응답을 캡처하기 위한 래퍼
type responseRecorder struct {
	http.ResponseWriter
	statusCode int
	body       *bytes.Buffer
}

func (rec *responseRecorder) WriteHeader(code int) {
	rec.statusCode = code
	rec.ResponseWriter.WriteHeader(code)
}

func (rec *responseRecorder) Write(b []byte) (int, error) {
	rec.body.Write(b)
	return rec.ResponseWriter.Write(b)
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}



