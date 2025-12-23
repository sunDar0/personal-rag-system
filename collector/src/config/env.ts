import dotenv from "dotenv";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { z } from "zod";

// __dirname 대체 (ESM 환경)
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// .env 파일 경로 후보들 (우선순위 순)
const envPaths = [
  path.resolve(process.cwd(), ".env"), // 현재 디렉토리
  path.resolve(process.cwd(), "../.env"), // 상위 디렉토리
  path.resolve(__dirname, "../../../.env"), // collector/src/config → 프로젝트 루트
  path.resolve(__dirname, "../../../../.env"), // 빌드 후 dist 경로 대응
];

// 존재하는 첫 번째 .env 파일 로드
const envPath = envPaths.find((p) => fs.existsSync(p));
if (envPath) {
  dotenv.config({ path: envPath });
  console.log(`📁 환경 변수 로드: ${envPath}`);
} else {
  console.warn(
    "⚠️ .env 파일을 찾을 수 없습니다. 시스템 환경 변수를 사용합니다."
  );
}

/**
 * 환경 변수 스키마 정의
 * zod로 타입 안전하게 검증
 */
const envSchema = z.object({
  // 데이터베이스
  DB_HOST: z.string().default("localhost"),
  DB_PORT: z.coerce.number().default(5432),
  DB_USER: z.string().default("postgres"),
  DB_PASSWORD: z.string(),
  DB_NAME: z.string().default("dev_brain"),

  // GitHub
  GITHUB_TOKEN: z.string(),
  // 동기화 모드: "repos" | "user" | "org" | "me"
  GITHUB_SYNC_MODE: z.enum(["repos", "user", "org", "me"]).default("repos"),
  // 모드별 설정
  GITHUB_REPOS: z
    .string()
    .optional()
    .transform((val) => val?.split(",").filter(Boolean) ?? []),
  GITHUB_USER: z.string().optional(), // user 모드용
  GITHUB_ORG: z.string().optional(), // org 모드용
  // 필터 옵션
  GITHUB_INCLUDE_PRIVATE: z
    .string()
    .optional()
    .transform((val) => val === "true"),
  GITHUB_INCLUDE_FORKS: z
    .string()
    .optional()
    .transform((val) => val === "true"),

  // Gemini API
  GOOGLE_API_KEY: z.string(),

  // 선택사항
  SYNC_CRON: z.string().default("0 */6 * * *"),
  CHUNK_SIZE: z.coerce.number().default(1000),
  CHUNK_OVERLAP: z.coerce.number().default(200),
});

/**
 * 환경 변수 파싱 및 검증
 */
function loadEnv() {
  const result = envSchema.safeParse(process.env);

  if (!result.success) {
    console.error("❌ 환경 변수 검증 실패:");
    for (const issue of result.error.issues) {
      console.error(`   - ${issue.path.join(".")}: ${issue.message}`);
    }
    process.exit(1);
  }

  return result.data;
}

export const env = loadEnv();
export type Env = z.infer<typeof envSchema>;
