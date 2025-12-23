import { GoogleGenerativeAI } from "@google/generative-ai";
import { env } from "../config/env.js";

/**
 * Gemini API 클라이언트
 */
const genAI = new GoogleGenerativeAI(env.GOOGLE_API_KEY);

/**
 * 임베딩 모델
 * text-embedding-004: 768차원 벡터
 */
const embeddingModel = genAI.getGenerativeModel({
  model: "text-embedding-004",
});

/**
 * 단일 텍스트 임베딩 생성
 */
export async function embedText(text: string): Promise<number[]> {
  try {
    const result = await embeddingModel.embedContent(text);
    return result.embedding.values;
  } catch (error) {
    console.error("❌ 임베딩 생성 실패:", error);
    throw error;
  }
}

/**
 * 배치 임베딩 생성 (Rate Limit 고려)
 * Gemini API는 분당 요청 수 제한이 있으므로 적절한 딜레이 추가
 */
export async function embedTexts(
  texts: string[],
  options: {
    batchSize?: number;
    delayMs?: number;
    onProgress?: (current: number, total: number) => void;
  } = {}
): Promise<number[][]> {
  const { batchSize = 5, delayMs = 200, onProgress } = options;
  const results: number[][] = [];

  for (let i = 0; i < texts.length; i += batchSize) {
    const batch = texts.slice(i, i + batchSize);

    // 배치 내 병렬 처리
    const batchResults = await Promise.all(
      batch.map((text) => embedText(text))
    );

    results.push(...batchResults);

    // 진행률 콜백
    if (onProgress) {
      onProgress(Math.min(i + batchSize, texts.length), texts.length);
    }

    // Rate Limit 방지를 위한 딜레이
    if (i + batchSize < texts.length) {
      await sleep(delayMs);
    }
  }

  return results;
}

/**
 * 딜레이 유틸리티
 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 임베딩 차원 (Gemini text-embedding-004)
 */
export const EMBEDDING_DIMENSION = 768;
