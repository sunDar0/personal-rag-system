import CryptoJS from "crypto-js";
import { splitCode } from "../chunking/splitter.js";
import { env } from "../config/env.js";
import {
  deleteChunksByDocumentId,
  getDocumentByUrl,
  getStats,
  insertChunksWithEmbeddings,
  upsertDocument,
} from "../db/repository.js";
import { embedTexts } from "../embedding/gemini.js";
import {
  detectLanguage,
  getFileContent,
  getMyRepos,
  getOrgRepos,
  getRepoTree,
  getUserRepos,
  parseRepoFullName,
  type GitHubFile,
} from "../sources/github/client.js";

/**
 * 동기화 결과
 */
interface SyncResult {
  added: number;
  updated: number;
  skipped: number;
  errors: number;
}

/**
 * 콘텐츠 해시 생성
 */
function hashContent(content: string): string {
  return CryptoJS.MD5(content).toString();
}

/**
 * 단일 파일 처리
 */
async function processFile(
  owner: string,
  repo: string,
  file: GitHubFile,
  repoFullName: string
): Promise<"added" | "updated" | "skipped" | "errors"> {
  const sourceUrl = `https://github.com/${repoFullName}/blob/main/${file.path}`;
  const contentHash = hashContent(file.content);

  try {
    // 기존 문서 확인
    const existingDoc = await getDocumentByUrl(sourceUrl);

    // 변경 없으면 스킵
    if (existingDoc && existingDoc.contentHash === contentHash) {
      return "skipped";
    }

    // 언어 감지 및 청킹
    const language = detectLanguage(file.path);
    const chunks = splitCode(file.content, file.path, language);

    if (chunks.length === 0) {
      return "skipped";
    }

    // 임베딩 생성
    console.log(`   📝 임베딩 생성 중: ${file.path} (${chunks.length}개 청크)`);
    const embeddings = await embedTexts(
      chunks.map((c) => c.content),
      {
        batchSize: 3,
        delayMs: 300,
        onProgress: (current, total) => {
          process.stdout.write(`\r   ⏳ ${current}/${total} 청크 처리 중...`);
        },
      }
    );
    console.log(""); // 줄바꿈

    // 기존 문서가 있으면 청크 삭제
    if (existingDoc) {
      await deleteChunksByDocumentId(existingDoc.id);
    }

    // 문서 저장
    const documentId = await upsertDocument({
      sourceType: "GITHUB",
      sourceUrl,
      title: file.path.split("/").pop() || file.path,
      contentHash,
    });

    // 청크 저장
    await insertChunksWithEmbeddings(
      chunks.map((chunk, index) => ({
        chunk: {
          documentId,
          chunkIndex: index,
          content: chunk.content,
          metadata: chunk.metadata,
        },
        embedding: embeddings[index],
      }))
    );

    return existingDoc ? "updated" : "added";
  } catch (error) {
    console.error(`   ❌ 파일 처리 실패: ${file.path}`, error);
    return "errors";
  }
}

/**
 * 단일 레포지토리 동기화
 */
async function syncRepository(repoFullName: string): Promise<SyncResult> {
  const { owner, repo } = parseRepoFullName(repoFullName);
  console.log(`\n📦 레포지토리 동기화: ${repoFullName}`);

  const result: SyncResult = { added: 0, updated: 0, skipped: 0, errors: 0 };

  try {
    // 파일 트리 조회
    const files = await getRepoTree(owner, repo);
    console.log(`   📂 ${files.length}개 파일 발견`);

    // 각 파일 처리
    for (const fileInfo of files) {
      const file = await getFileContent(owner, repo, fileInfo.path);

      if (!file) {
        result.errors++;
        continue;
      }

      const status = await processFile(owner, repo, file, repoFullName);
      result[status]++;

      // 진행률 출력
      const total =
        result.added + result.updated + result.skipped + result.errors;
      console.log(`   [${total}/${files.length}] ${file.path} → ${status}`);
    }
  } catch (error) {
    console.error(`❌ 레포지토리 동기화 실패: ${repoFullName}`, error);
    result.errors++;
  }

  return result;
}

/**
 * 동기화 대상 레포지토리 목록 가져오기
 */
async function getTargetRepos(): Promise<string[]> {
  const mode = env.GITHUB_SYNC_MODE;

  console.log(`📋 동기화 모드: ${mode}`);

  switch (mode) {
    case "repos":
      // 직접 지정한 레포지토리
      if (!env.GITHUB_REPOS || env.GITHUB_REPOS.length === 0) {
        throw new Error("GITHUB_REPOS가 설정되지 않았습니다.");
      }
      console.log(`   지정된 레포: ${env.GITHUB_REPOS.join(", ")}`);
      return env.GITHUB_REPOS;

    case "me":
      // 인증된 사용자의 모든 레포지토리
      console.log("   내 모든 레포지토리 조회 중...");
      const myRepos = await getMyRepos({
        includePrivate: env.GITHUB_INCLUDE_PRIVATE,
        includeForks: env.GITHUB_INCLUDE_FORKS,
      });
      return myRepos.map((r) => r.fullName);

    case "user":
      // 특정 사용자의 레포지토리
      if (!env.GITHUB_USER) {
        throw new Error("GITHUB_USER가 설정되지 않았습니다.");
      }
      console.log(`   ${env.GITHUB_USER}의 레포지토리 조회 중...`);
      const userRepos = await getUserRepos(env.GITHUB_USER);
      return userRepos.map((r) => r.fullName);

    case "org":
      // 조직의 레포지토리
      if (!env.GITHUB_ORG) {
        throw new Error("GITHUB_ORG가 설정되지 않았습니다.");
      }
      console.log(`   ${env.GITHUB_ORG} 조직의 레포지토리 조회 중...`);
      const orgRepos = await getOrgRepos(env.GITHUB_ORG);
      return orgRepos.map((r) => r.fullName);

    default:
      throw new Error(`알 수 없는 동기화 모드: ${mode}`);
  }
}

/**
 * 모든 레포지토리 동기화
 */
export async function syncAll(): Promise<void> {
  console.log("🚀 동기화 시작");

  const startTime = Date.now();
  const totalResult: SyncResult = {
    added: 0,
    updated: 0,
    skipped: 0,
    errors: 0,
  };

  // 대상 레포지토리 목록 가져오기
  const repos = await getTargetRepos();
  console.log(`\n📦 총 ${repos.length}개 레포지토리 동기화 예정\n`);

  for (const repoFullName of repos) {
    const result = await syncRepository(repoFullName.trim());

    totalResult.added += result.added;
    totalResult.updated += result.updated;
    totalResult.skipped += result.skipped;
    totalResult.errors += result.errors;
  }

  const elapsed = ((Date.now() - startTime) / 1000).toFixed(1);
  const stats = await getStats();

  console.log("\n" + "=".repeat(50));
  console.log("✅ 동기화 완료!");
  console.log(`   ⏱️  소요 시간: ${elapsed}초`);
  console.log(`   ➕ 추가: ${totalResult.added}`);
  console.log(`   🔄 업데이트: ${totalResult.updated}`);
  console.log(`   ⏭️  스킵: ${totalResult.skipped}`);
  console.log(`   ❌ 에러: ${totalResult.errors}`);
  console.log(`   📊 전체 문서: ${stats.documents}개, 청크: ${stats.chunks}개`);
  console.log("=".repeat(50));
}
