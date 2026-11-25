-- Enable pgvector extension if not present
CREATE EXTENSION IF NOT EXISTS vector;

-- Create known_issues table for vector similarity matching
CREATE TABLE IF NOT EXISTS known_issues (
                              issue_id VARCHAR(64) PRIMARY KEY,
                              pattern_name VARCHAR(128) NOT NULL,
                              category VARCHAR(64) NOT NULL,
                              description TEXT NOT NULL,
                              recommended_action TEXT NOT NULL,
                              embedding vector(1536), -- Standard size for OpenAI text-embedding-3-small / nomic-embed-text
                              created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Index for HNSW fast vector cosine similarity search
CREATE INDEX IF NOT EXISTS idx_known_issues_embedding ON known_issues
    USING hnsw (embedding vector_cosine_ops);