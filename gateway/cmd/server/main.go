package main

import (
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"

	"github.com/devbrain/gateway/internal/cache"
	"github.com/devbrain/gateway/internal/config"
	"github.com/devbrain/gateway/internal/handler"
	"github.com/devbrain/gateway/internal/middleware"
)

func main() {
	log.Println(strings.Repeat("=", 50))
	log.Println("🚀 DevBrain Gateway 시작")
	log.Println(strings.Repeat("=", 50))

	// 설정 로드
	cfg := config.Load()
	log.Printf("📋 설정 로드 완료: Backend=%s, Redis=%s", cfg.BackendURL, cfg.RedisAddr)

	// Redis 클라이언트 초기화
	redisClient := cache.NewRedisClient(cfg.RedisAddr, cfg.RedisPassword)
	defer redisClient.Close()

	// 핸들러 생성
	proxyHandler := handler.NewProxyHandler(cfg.BackendURL, redisClient, cfg)

	// 미들웨어 체인 구성
	var h http.Handler = proxyHandler

	// Rate Limiter 적용
	rateLimiter := middleware.NewRateLimiter(cfg.RateLimit, cfg.RateBurst)
	h = rateLimiter.Middleware(h)

	// 로깅 미들웨어
	h = middleware.LoggingMiddleware(h)

	// CORS 미들웨어
	h = middleware.CORSMiddleware(h)

	// 서버 시작
	server := &http.Server{
		Addr:    ":" + cfg.Port,
		Handler: h,
	}

	// Graceful shutdown
	go func() {
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
		<-sigChan

		log.Println("🛑 서버 종료 중...")
		server.Close()
	}()

	log.Printf("✅ Gateway 서버 시작: http://localhost:%s", cfg.Port)
	if err := server.ListenAndServe(); err != http.ErrServerClosed {
		log.Fatalf("❌ 서버 오류: %v", err)
	}

	log.Println("👋 서버 종료 완료")
}

