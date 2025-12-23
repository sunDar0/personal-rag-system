import { closeConnection, testConnection } from "./db/client.js";
import { syncAll } from "./sync/incremental.js";

/**
 * 메인 진입점
 */
async function main() {
  console.log("=".repeat(50));
  console.log("🧠 My Dev Brain - Collector");
  console.log("=".repeat(50));

  try {
    // 1. DB 연결 테스트
    const connected = await testConnection();
    if (!connected) {
      process.exit(1);
    }

    // 2. 동기화 실행
    await syncAll();

    // 3. 정상 종료
    await closeConnection();
    process.exit(0);
  } catch (error) {
    console.error("❌ 치명적 오류:", error);
    await closeConnection();
    process.exit(1);
  }
}

// 실행
main();
