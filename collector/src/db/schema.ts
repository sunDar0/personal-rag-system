import {
  bigint,
  bigserial,
  index,
  jsonb,
  pgTable,
  text,
  timestamp,
  uuid,
  varchar,
} from "drizzle-orm/pg-core";

/**
 * documents 테이블 - 원본 문서 메타데이터
 */
export const documents = pgTable(
  "documents",
  {
    id: bigserial("id", { mode: "number" }).primaryKey(),
    sourceType: varchar("source_type", { length: 50 }).notNull(),
    sourceUrl: text("source_url").notNull().unique(),
    title: text("title"),
    contentHash: varchar("content_hash", { length: 64 }).notNull(),
    createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).defaultNow(),
  },
  (table) => [index("idx_documents_source_type").on(table.sourceType)]
);

/**
 * document_chunks 테이블 - 벡터 + 텍스트 청크
 *
 * 참고: embedding 컬럼은 VECTOR(768) 타입이지만,
 * Drizzle에서 직접 지원하지 않으므로 raw SQL로 처리
 */
export const documentChunks = pgTable(
  "document_chunks",
  {
    id: uuid("id").defaultRandom().primaryKey(),
    documentId: bigint("document_id", { mode: "number" })
      .notNull()
      .references(() => documents.id, { onDelete: "cascade" }),
    chunkIndex: bigint("chunk_index", { mode: "number" }).notNull(),
    content: text("content").notNull(),
    metadata: jsonb("metadata").default({}),
    // embedding은 별도로 처리 (pgvector)
    createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
  },
  (table) => [index("idx_chunks_document_id").on(table.documentId)]
);

/**
 * 청크 메타데이터 타입
 */
export interface ChunkMetadata {
  filePath: string;
  functionName?: string;
  className?: string;
  language: string;
  startLine: number;
  endLine: number;
}

/**
 * 타입 추론
 */
export type Document = typeof documents.$inferSelect;
export type NewDocument = typeof documents.$inferInsert;
export type DocumentChunk = typeof documentChunks.$inferSelect;
export type NewDocumentChunk = typeof documentChunks.$inferInsert;
