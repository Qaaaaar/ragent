-- v1.2 -> v1.3: RAG working and long-term memory.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS t_rag_memory (
    id                     VARCHAR(20) NOT NULL PRIMARY KEY,
    scope_type             VARCHAR(16) NOT NULL,
    owner_user_id          VARCHAR(20),
    source_user_id         VARCHAR(20),
    memory_level           VARCHAR(32) NOT NULL,
    memory_type            VARCHAR(64) NOT NULL,
    title                  VARCHAR(128) NOT NULL,
    content                TEXT NOT NULL,
    keywords               JSONB DEFAULT '[]'::jsonb,
    source_conversation_id VARCHAR(20),
    source_message_ids     JSONB DEFAULT '[]'::jsonb,
    confidence             DOUBLE PRECISION DEFAULT 1,
    importance             INTEGER DEFAULT 50,
    status                 VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    expire_time            TIMESTAMP,
    last_access_time       TIMESTAMP,
    access_count           INTEGER DEFAULT 0,
    embedding              vector(1536),
    create_by              VARCHAR(20),
    update_by              VARCHAR(20),
    create_time            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted                SMALLINT DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_rag_memory_scope_owner
    ON t_rag_memory (scope_type, owner_user_id, status, deleted);
CREATE INDEX IF NOT EXISTS idx_rag_memory_status
    ON t_rag_memory (status, memory_level, memory_type);
CREATE INDEX IF NOT EXISTS idx_rag_memory_keywords
    ON t_rag_memory USING gin(keywords);
CREATE INDEX IF NOT EXISTS idx_rag_memory_embedding
    ON t_rag_memory USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE t_rag_memory IS 'RAG中长期与长期记忆表';
