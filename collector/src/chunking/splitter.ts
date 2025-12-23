import { env } from "../config/env.js";
import type { ChunkMetadata } from "../db/schema.js";

/**
 * 청크 결과
 */
export interface Chunk {
  content: string;
  metadata: ChunkMetadata;
}

/**
 * 언어별 분할자 정의
 */
const LANGUAGE_SEPARATORS: Record<string, string[]> = {
  typescript: [
    "\nexport class ",
    "\nexport interface ",
    "\nexport function ",
    "\nexport const ",
    "\nexport default ",
    "\nclass ",
    "\ninterface ",
    "\nfunction ",
    "\nconst ",
    "\n}\n",
  ],
  javascript: [
    "\nexport class ",
    "\nexport function ",
    "\nexport const ",
    "\nexport default ",
    "\nclass ",
    "\nfunction ",
    "\nconst ",
    "\n}\n",
  ],
  java: [
    "\npublic class ",
    "\nprivate class ",
    "\nclass ",
    "\npublic interface ",
    "\ninterface ",
    "\npublic void ",
    "\nprivate void ",
    "\npublic static ",
    "\n    public ",
    "\n    private ",
    "\n}\n",
  ],
  go: ["\nfunc ", "\ntype ", "\nvar ", "\nconst ", "\n}\n"],
  python: ["\nclass ", "\ndef ", "\nasync def ", "\n\n"],
  markdown: ["\n## ", "\n### ", "\n#### ", "\n---\n", "\n\n\n"],
};

/**
 * 함수/클래스 이름 추출
 */
function extractIdentifier(
  content: string,
  language: string
): { functionName?: string; className?: string } {
  const result: { functionName?: string; className?: string } = {};

  // 클래스 이름 추출
  const classMatch = content.match(/class\s+(\w+)/);
  if (classMatch) {
    result.className = classMatch[1];
  }

  // 함수 이름 추출
  let funcMatch: RegExpMatchArray | null = null;

  switch (language) {
    case "typescript":
    case "javascript":
      funcMatch = content.match(
        /(?:function|const|let|var)\s+(\w+)|(\w+)\s*[=:]\s*(?:async\s*)?\(/
      );
      break;
    case "java":
      funcMatch = content.match(
        /(?:public|private|protected)?\s*(?:static\s+)?(?:\w+\s+)?(\w+)\s*\(/
      );
      break;
    case "go":
      funcMatch = content.match(/func\s+(?:\([^)]+\)\s+)?(\w+)/);
      break;
    case "python":
      funcMatch = content.match(/def\s+(\w+)/);
      break;
  }

  if (funcMatch) {
    result.functionName = funcMatch[1] || funcMatch[2];
  }

  return result;
}

/**
 * 텍스트를 재귀적으로 분할
 */
function recursiveSplit(
  text: string,
  separators: string[],
  chunkSize: number,
  overlap: number
): string[] {
  if (text.length <= chunkSize) {
    return [text];
  }

  // 현재 분할자로 분할 시도
  for (const sep of separators) {
    if (text.includes(sep)) {
      const parts = text.split(sep);
      const chunks: string[] = [];
      let current = "";

      for (let i = 0; i < parts.length; i++) {
        const part = (i > 0 ? sep : "") + parts[i];

        if (current.length + part.length <= chunkSize) {
          current += part;
        } else {
          if (current.trim()) {
            chunks.push(current.trim());
          }
          // 오버랩 처리
          const overlapStart = Math.max(0, current.length - overlap);
          current = current.slice(overlapStart) + part;
        }
      }

      if (current.trim()) {
        chunks.push(current.trim());
      }

      // 여전히 큰 청크가 있으면 재귀 분할
      return chunks.flatMap((chunk) =>
        chunk.length > chunkSize
          ? recursiveSplit(chunk, separators.slice(1), chunkSize, overlap)
          : [chunk]
      );
    }
  }

  // 분할자가 없으면 강제로 크기 기준 분할
  const chunks: string[] = [];
  for (let i = 0; i < text.length; i += chunkSize - overlap) {
    chunks.push(text.slice(i, i + chunkSize).trim());
  }

  return chunks.filter((c) => c.length > 0);
}

/**
 * 코드 파일을 청크로 분할
 */
export function splitCode(
  content: string,
  filePath: string,
  language: string
): Chunk[] {
  const separators = LANGUAGE_SEPARATORS[language] || ["\n\n", "\n"];
  const chunkSize = env.CHUNK_SIZE;
  const overlap = env.CHUNK_OVERLAP;

  // 재귀적 분할
  const textChunks = recursiveSplit(content, separators, chunkSize, overlap);

  // 각 청크에 메타데이터 추가
  let lineOffset = 1;

  return textChunks.map((text, index) => {
    const lineCount = text.split("\n").length;
    const identifiers = extractIdentifier(text, language);

    // 파일 경로와 함수명을 청크 상단에 주입 (검색 정확도 향상)
    const enrichedContent = `// File: ${filePath}\n${
      identifiers.functionName
        ? `// Function: ${identifiers.functionName}\n`
        : ""
    }${
      identifiers.className ? `// Class: ${identifiers.className}\n` : ""
    }\n${text}`;

    const chunk: Chunk = {
      content: enrichedContent,
      metadata: {
        filePath,
        language,
        startLine: lineOffset,
        endLine: lineOffset + lineCount - 1,
        ...identifiers,
      },
    };

    lineOffset += lineCount;
    return chunk;
  });
}

/**
 * Markdown 문서를 청크로 분할
 */
export function splitMarkdown(content: string, filePath: string): Chunk[] {
  return splitCode(content, filePath, "markdown");
}
