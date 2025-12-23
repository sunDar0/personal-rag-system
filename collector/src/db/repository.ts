import { eq } from "drizzle-orm";
import { db, sql } from "./client.js";
import {
  documentChunks,
  documents,
  type NewDocument,
  type NewDocumentChunk,
} from "./schema.js";

/**
 * 문서 저장 또는 업데이트
 */
export async function upsertDocument(doc: NewDocument): Promise<number> {
  const result = await db
    .insert(documents)
    .values(doc)
    .onConflictDoUpdate({
      target: documents.sourceUrl,
      set: {
        title: doc.title,
        contentHash: doc.contentHash,
        updatedAt: new Date(),
      },
    })
    .returning({ id: documents.id });

  return result[0].id;
}

/**
 * 문서 해시로 조회 (변경 감지용)
 */
export async function getDocumentByUrl(sourceUrl: string) {
  const result = await db
    .select()
    .from(documents)
    .where(eq(documents.sourceUrl, sourceUrl))
    .limit(1);

  return result[0] ?? null;
}

/**
 * 문서의 모든 청크 삭제
 */
export async function deleteChunksByDocumentId(
  documentId: number
): Promise<void> {
  await db
    .delete(documentChunks)
    .where(eq(documentChunks.documentId, documentId));
}

/**
 * 청크 저장 (임베딩 포함)
 * pgvector는 Drizzle에서 직접 지원하지 않으므로 raw SQL 사용
 */
export async function insertChunkWithEmbedding(
  chunk: NewDocumentChunk,
  embedding: number[]
): Promise<string> {
  const embeddingStr = `[${embedding.join(",")}]`;

  const result = await sql`
    INSERT INTO document_chunks (document_id, chunk_index, content, metadata, embedding)
    VALUES (
      ${chunk.documentId},
      ${chunk.chunkIndex},
      ${chunk.content},
      ${JSON.stringify(chunk.metadata)},
      ${embeddingStr}::vector
    )
    RETURNING id
  `;

  return result[0].id;
}

/**
 * 여러 청크 일괄 저장
 */
export async function insertChunksWithEmbeddings(
  chunks: Array<{
    chunk: NewDocumentChunk;
    embedding: number[];
  }>
): Promise<string[]> {
  const ids: string[] = [];

  // 트랜잭션으로 일괄 처리
  await sql.begin(async (tx) => {
    for (const { chunk, embedding } of chunks) {
      const embeddingStr = `[${embedding.join(",")}]`;

      const result = await tx`
        INSERT INTO document_chunks (document_id, chunk_index, content, metadata, embedding)
        VALUES (
          ${chunk.documentId},
          ${chunk.chunkIndex},
          ${chunk.content},
          ${JSON.stringify(chunk.metadata)},
          ${embeddingStr}::vector
        )
        RETURNING id
      `;

      ids.push(result[0].id);
    }
  });

  return ids;
}

/**
 * 문서 삭제 (청크도 CASCADE로 함께 삭제)
 */
export async function deleteDocument(documentId: number): Promise<void> {
  await db.delete(documents).where(eq(documents.id, documentId));
}

/**
 * 통계 조회
 */
export async function getStats() {
  const docCount = await sql`SELECT COUNT(*) as count FROM documents`;
  const chunkCount = await sql`SELECT COUNT(*) as count FROM document_chunks`;

  return {
    documents: Number(docCount[0].count),
    chunks: Number(chunkCount[0].count),
  };
}
