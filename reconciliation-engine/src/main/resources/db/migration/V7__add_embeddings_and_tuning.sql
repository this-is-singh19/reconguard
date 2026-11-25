-- Migration: V7__add_embeddings_and_tuning.sql
-- Description: Adds missing vector column, applies HNSW index tuning, and prepares for bulk seeding.

-- 1. Add missing embedding column to reconciliation_failures
ALTER TABLE reconciliation_failures
ADD COLUMN IF NOT EXISTS context_embedding vector(1536);

-- 2. Recreate known_issues index with explicit tuning parameters
DROP INDEX IF EXISTS idx_known_issues_embedding;
CREATE INDEX idx_known_issues_embedding 
ON known_issues USING hnsw (embedding vector_cosine_ops) 
WITH (m = 16, ef_construction = 64);

-- 3. Create HNSW index for the new reconciliation_failures column
CREATE INDEX IF NOT EXISTS idx_reconciliation_failures_embedding 
ON reconciliation_failures USING hnsw (context_embedding vector_cosine_ops) 
WITH (m = 16, ef_construction = 64);

-- Note: You will need a separate V8 script or an application-level bootstrap process to insert the remaining 46 seed patterns to reach the 50 baseline requirement.