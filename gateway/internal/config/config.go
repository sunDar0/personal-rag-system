package config

import (
	"log"
	"os"
	"path/filepath"
	"strconv"

	"github.com/joho/godotenv"
)

// Config는 Gateway 설정을 담는 구조체
type Config struct {
	// 서버 설정
	Port string

	// Backend 설정
	BackendURL string

	// Redis 설정
	RedisAddr     string
	RedisPassword string

	// Rate Limiter 설정
	RateLimit float64 // 초당 요청 수
	RateBurst int     // 버스트 허용량

	// 캐시 설정
	CacheEnabled bool
	CacheTTL     int // 초 단위

	// 시맨틱 캐시 설정
	SimilarityThreshold float64 // 유사도 임계값 (0.0 ~ 1.0)
}

// Load는 환경 변수에서 설정을 로드
func Load() *Config {
	// .env 파일 로드 시도
	envPaths := []string{
		".env",
		"../.env",
		filepath.Join("..", "..", ".env"),
	}

	for _, path := range envPaths {
		if err := godotenv.Load(path); err == nil {
			log.Printf("📁 환경 변수 로드: %s", path)
			break
		}
	}

	return &Config{
		Port:                getEnv("GATEWAY_PORT", "8080"),
		BackendURL:          getEnv("BACKEND_URL", "http://localhost:8081"),
		RedisAddr:           getEnv("REDIS_HOST", "localhost") + ":" + getEnv("REDIS_PORT", "6379"),
		RedisPassword:       getEnv("REDIS_PASSWORD", ""),
		RateLimit:           getEnvFloat("RATE_LIMIT", 10.0), // 초당 요청 수
		RateBurst:           getEnvInt("RATE_BURST", 20), // 버스트 허용량
		CacheEnabled:        getEnvBool("CACHE_ENABLED", true),
		CacheTTL:            getEnvInt("CACHE_TTL", 3600), // 캐시 유지 시간 (초)
		SimilarityThreshold: getEnvFloat("SIMILARITY_THRESHOLD", 0.95), // 유사도 임계값 (0.0 ~ 1.0)
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if i, err := strconv.Atoi(value); err == nil {
			return i
		}
	}
	return defaultValue
}

func getEnvFloat(key string, defaultValue float64) float64 {
	if value := os.Getenv(key); value != "" {
		if f, err := strconv.ParseFloat(value, 64); err == nil {
			return f
		}
	}
	return defaultValue
}

func getEnvBool(key string, defaultValue bool) bool {
	if value := os.Getenv(key); value != "" {
		return value == "true" || value == "1"
	}
	return defaultValue
}

