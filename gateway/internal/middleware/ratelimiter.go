package middleware

import (
	"log"
	"net/http"
	"sync"

	"golang.org/x/time/rate"
)

// RateLimiter는 IP 기반 Rate Limiting을 구현
type RateLimiter struct {
	limiters map[string]*rate.Limiter
	mu       sync.RWMutex
	rate     rate.Limit
	burst    int
}

// NewRateLimiter는 새로운 Rate Limiter 생성
// r: 초당 허용 요청 수
// b: 버스트 허용량
func NewRateLimiter(r float64, b int) *RateLimiter {
	return &RateLimiter{
		limiters: make(map[string]*rate.Limiter),
		rate:     rate.Limit(r),
		burst:    b,
	}
}

// getLimiter는 IP별 Limiter 반환 (없으면 생성)
func (rl *RateLimiter) getLimiter(ip string) *rate.Limiter {
	rl.mu.RLock()
	limiter, exists := rl.limiters[ip]
	rl.mu.RUnlock()

	if exists {
		return limiter
	}

	rl.mu.Lock()
	defer rl.mu.Unlock()

	// Double-check
	if limiter, exists = rl.limiters[ip]; exists {
		return limiter
	}

	limiter = rate.NewLimiter(rl.rate, rl.burst)
	rl.limiters[ip] = limiter

	return limiter
}

// Middleware는 Rate Limiting 미들웨어
func (rl *RateLimiter) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 클라이언트 IP 추출
		ip := r.RemoteAddr
		if forwarded := r.Header.Get("X-Forwarded-For"); forwarded != "" {
			ip = forwarded
		}

		limiter := rl.getLimiter(ip)

		if !limiter.Allow() {
			log.Printf("⚠️ Rate Limit 초과: %s", ip)
			http.Error(w, `{"error": "Too Many Requests", "message": "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."}`, http.StatusTooManyRequests)
			return
		}

		next.ServeHTTP(w, r)
	})
}

// CleanupOldLimiters는 오래된 Limiter 정리 (메모리 관리용)
func (rl *RateLimiter) CleanupOldLimiters() {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	// 간단한 구현: 일정 수 이상이면 전체 초기화
	if len(rl.limiters) > 10000 {
		rl.limiters = make(map[string]*rate.Limiter)
		log.Println("🧹 Rate Limiter 캐시 정리")
	}
}



