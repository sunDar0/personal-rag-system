import { drizzle } from "drizzle-orm/postgres-js";
import postgres from "postgres";
import { env } from "../config/env.js";
import * as schema from "./schema.js";

/**
 * PostgreSQL 연결 URL 생성
 */
const connectionString = `postgres://${env.DB_USER}:${env.DB_PASSWORD}@${env.DB_HOST}:${env.DB_PORT}/${env.DB_NAME}`;

/**
 * postgres.js 클라이언트 (쿼리용)
 */
export const sql = postgres(connectionString, {
  max: 10, // 최대 연결 수
  idle_timeout: 20,
  connect_timeout: 10,
});

/**
 * Drizzle ORM 인스턴스
 */
export const db = drizzle(sql, { schema });

/**
 * 연결 테스트
 */
export async function testConnection(): Promise<boolean> {
  try {
    await sql`SELECT 1`;
    console.log("✅ PostgreSQL 연결 성공");
    return true;
  } catch (error) {
    console.error("❌ PostgreSQL 연결 실패:", error);
    return false;
  }
}

/**
 * 연결 종료
 */
export async function closeConnection(): Promise<void> {
  await sql.end();
  console.log("📤 PostgreSQL 연결 종료");
}
