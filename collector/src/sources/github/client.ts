import { Octokit } from "@octokit/rest";
import { env } from "../../config/env.js";

/**
 * GitHub API 클라이언트
 */
export const octokit = new Octokit({
  auth: env.GITHUB_TOKEN,
});

/**
 * GitHub 파일 정보
 */
export interface GitHubFile {
  path: string;
  sha: string;
  content: string;
  size: number;
}

/**
 * 지원하는 파일 확장자
 */
const SUPPORTED_EXTENSIONS = [
  ".ts",
  ".tsx",
  ".js",
  ".jsx", // TypeScript/JavaScript
  ".java", // Java
  ".go", // Go
  ".py", // Python
  ".md", // Markdown
];

/**
 * 파일이 지원되는 타입인지 확인
 */
export function isSupportedFile(path: string): boolean {
  return SUPPORTED_EXTENSIONS.some((ext) => path.endsWith(ext));
}

/**
 * 레포지토리의 파일 트리 가져오기
 */
export async function getRepoTree(
  owner: string,
  repo: string,
  branch = "main"
): Promise<Array<{ path: string; sha: string }>> {
  try {
    // 기본 브랜치 정보 조회
    const { data: repoData } = await octokit.repos.get({ owner, repo });
    const defaultBranch = repoData.default_branch || branch;

    // 트리 조회 (recursive)
    const { data: tree } = await octokit.git.getTree({
      owner,
      repo,
      tree_sha: defaultBranch,
      recursive: "true",
    });

    // 지원되는 파일만 필터링
    return tree.tree
      .filter(
        (item) =>
          item.type === "blob" && item.path && isSupportedFile(item.path)
      )
      .map((item) => ({
        path: item.path!,
        sha: item.sha!,
      }));
  } catch (error) {
    console.error(`❌ 레포지토리 트리 조회 실패: ${owner}/${repo}`, error);
    throw error;
  }
}

/**
 * 파일 내용 가져오기
 */
export async function getFileContent(
  owner: string,
  repo: string,
  path: string
): Promise<GitHubFile | null> {
  try {
    const { data } = await octokit.repos.getContent({
      owner,
      repo,
      path,
    });

    // 파일인 경우에만 처리
    if ("content" in data && data.type === "file") {
      const content = Buffer.from(data.content, "base64").toString("utf-8");

      return {
        path: data.path,
        sha: data.sha,
        content,
        size: data.size,
      };
    }

    return null;
  } catch (error) {
    console.error(`❌ 파일 조회 실패: ${path}`, error);
    return null;
  }
}

/**
 * owner/repo 형식 파싱
 */
export function parseRepoFullName(fullName: string): {
  owner: string;
  repo: string;
} {
  const [owner, repo] = fullName.split("/");

  if (!owner || !repo) {
    throw new Error(
      `잘못된 레포지토리 형식: ${fullName} (owner/repo 형식이어야 합니다)`
    );
  }

  return { owner, repo };
}

/**
 * 언어 감지 (확장자 기반)
 */
export function detectLanguage(path: string): string {
  if (path.endsWith(".ts") || path.endsWith(".tsx")) return "typescript";
  if (path.endsWith(".js") || path.endsWith(".jsx")) return "javascript";
  if (path.endsWith(".java")) return "java";
  if (path.endsWith(".go")) return "go";
  if (path.endsWith(".py")) return "python";
  if (path.endsWith(".md")) return "markdown";
  return "unknown";
}

/**
 * 레포지토리 정보
 */
export interface RepoInfo {
  fullName: string; // owner/repo
  name: string;
  isPrivate: boolean;
  language: string | null;
  updatedAt: string;
}

/**
 * 인증된 사용자의 모든 레포지토리 가져오기
 * (private 레포 포함)
 */
export async function getMyRepos(options?: {
  includePrivate?: boolean;
  includeForks?: boolean;
}): Promise<RepoInfo[]> {
  const { includePrivate = true, includeForks = false } = options ?? {};

  try {
    const repos: RepoInfo[] = [];
    let page = 1;

    while (true) {
      const { data } = await octokit.repos.listForAuthenticatedUser({
        visibility: includePrivate ? "all" : "public",
        sort: "updated",
        per_page: 100,
        page,
      });

      if (data.length === 0) break;

      for (const repo of data) {
        // Fork 제외 옵션
        if (!includeForks && repo.fork) continue;

        repos.push({
          fullName: repo.full_name,
          name: repo.name,
          isPrivate: repo.private,
          language: repo.language,
          updatedAt: repo.updated_at ?? "",
        });
      }

      page++;
    }

    console.log(`📦 ${repos.length}개 레포지토리 발견`);
    return repos;
  } catch (error) {
    console.error("❌ 레포지토리 목록 조회 실패:", error);
    throw error;
  }
}

/**
 * 특정 사용자의 공개 레포지토리 가져오기
 */
export async function getUserRepos(username: string): Promise<RepoInfo[]> {
  try {
    const repos: RepoInfo[] = [];
    let page = 1;

    while (true) {
      const { data } = await octokit.repos.listForUser({
        username,
        sort: "updated",
        per_page: 100,
        page,
      });

      if (data.length === 0) break;

      for (const repo of data) {
        if (repo.fork) continue; // Fork 제외

        repos.push({
          fullName: repo.full_name,
          name: repo.name,
          isPrivate: repo.private,
          language: repo.language ?? null,
          updatedAt: repo.updated_at ?? "",
        });
      }

      page++;
    }

    console.log(`📦 ${username}의 ${repos.length}개 레포지토리 발견`);
    return repos;
  } catch (error) {
    console.error(`❌ ${username} 레포지토리 조회 실패:`, error);
    throw error;
  }
}

/**
 * 조직의 레포지토리 가져오기
 */
export async function getOrgRepos(org: string): Promise<RepoInfo[]> {
  try {
    const repos: RepoInfo[] = [];
    let page = 1;

    while (true) {
      const { data } = await octokit.repos.listForOrg({
        org,
        sort: "updated",
        per_page: 100,
        page,
      });

      if (data.length === 0) break;

      for (const repo of data) {
        repos.push({
          fullName: repo.full_name,
          name: repo.name,
          isPrivate: repo.private,
          language: repo.language ?? null,
          updatedAt: repo.updated_at ?? "",
        });
      }

      page++;
    }

    console.log(`📦 ${org} 조직의 ${repos.length}개 레포지토리 발견`);
    return repos;
  } catch (error) {
    console.error(`❌ ${org} 조직 레포지토리 조회 실패:`, error);
    throw error;
  }
}
