package cache

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"log"
	"strings"
	"time"

	"github.com/go-redis/redis/v8"
)

// RedisClient는 Redis 연결을 관리하는 클라이언트
type RedisClient struct {
	client *redis.Client
	ctx    context.Context
}

// CachedResponse는 캐시된 응답 구조체
type CachedResponse struct {
	Query     string    `json:"query"`
	Response  string    `json:"response"`
	CreatedAt time.Time `json:"created_at"`
}

// NewRedisClient는 새로운 Redis 클라이언트 생성
func NewRedisClient(addr, password string) *RedisClient {
	client := redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: password,
		DB:       0,
	})

	ctx := context.Background()

	// 연결 테스트
	if _, err := client.Ping(ctx).Result(); err != nil {
		log.Printf("⚠️ Redis 연결 실패: %v (캐시 비활성화)", err)
	} else {
		log.Println("✅ Redis 연결 성공")
	}

	return &RedisClient{
		client: client,
		ctx:    ctx,
	}
}

// Close는 Redis 연결 종료
func (r *RedisClient) Close() error {
	return r.client.Close()
}

// IsConnected는 Redis 연결 상태 확인
func (r *RedisClient) IsConnected() bool {
	_, err := r.client.Ping(r.ctx).Result()
	return err == nil
}

// generateCacheKey는 쿼리에서 캐시 키 생성
func generateCacheKey(query string) string {
	// 쿼리 정규화: 소문자 변환, 공백 정리
	normalized := strings.ToLower(strings.TrimSpace(query))
	normalized = strings.Join(strings.Fields(normalized), " ")

	// MD5 해시 생성
	hash := md5.Sum([]byte(normalized))
	return "chat:" + hex.EncodeToString(hash[:])
}

// Get는 캐시에서 응답 조회
func (r *RedisClient) Get(query string) (*CachedResponse, error) {
	key := generateCacheKey(query)

	data, err := r.client.Get(r.ctx, key).Bytes()
	if err == redis.Nil {
		return nil, nil // 캐시 미스
	}
	if err != nil {
		return nil, err
	}

	var cached CachedResponse
	if err := json.Unmarshal(data, &cached); err != nil {
		return nil, err
	}

	return &cached, nil
}

// Set는 응답을 캐시에 저장
func (r *RedisClient) Set(query, response string, ttl time.Duration) error {
	key := generateCacheKey(query)

	cached := CachedResponse{
		Query:     query,
		Response:  response,
		CreatedAt: time.Now(),
	}

	data, err := json.Marshal(cached)
	if err != nil {
		return err
	}

	return r.client.Set(r.ctx, key, data, ttl).Err()
}

// Delete는 캐시에서 항목 삭제
func (r *RedisClient) Delete(query string) error {
	key := generateCacheKey(query)
	return r.client.Del(r.ctx, key).Err()
}

// GetStats는 캐시 통계 조회
func (r *RedisClient) GetStats() (map[string]any, error) {
	info, err := r.client.Info(r.ctx, "stats").Result()
	if err != nil {
		return nil, err
	}

	// 키 개수 조회
	keys, err := r.client.Keys(r.ctx, "chat:*").Result()
	if err != nil {
		return nil, err
	}

	return map[string]any{
		"cached_queries": len(keys),
		"info":           info,
	}, nil
}



