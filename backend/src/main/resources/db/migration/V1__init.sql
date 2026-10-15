CREATE EXTENSION IF NOT EXISTS vector;

-- One row per uploaded file.
CREATE TABLE documents (
    id           UUID PRIMARY KEY,
    filename     TEXT        NOT NULL,
    content_type TEXT,
    size_bytes   BIGINT      NOT NULL,
    chunk_count  INTEGER     NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Chunk embeddings, in the layout Spring AI's PgVectorStore expects.
-- metadata.document_id links each chunk back to its row in `documents`.
-- vector(768) matches nomic-embed-text; change it if you switch embedding models.
CREATE TABLE vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  JSON,
    embedding vector(768)
);

CREATE INDEX vector_store_embedding_idx ON vector_store USING hnsw (embedding vector_cosine_ops);
CREATE INDEX vector_store_document_id_idx ON vector_store ((metadata ->> 'document_id'));
