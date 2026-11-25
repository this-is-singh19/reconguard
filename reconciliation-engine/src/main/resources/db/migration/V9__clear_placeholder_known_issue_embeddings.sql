-- Migration: V9__clear_placeholder_known_issue_embeddings.sql
-- Description: V6/V8 used to seed every known_issues.embedding with a constant placeholder vector,
-- which makes cosine similarity identical for all patterns. Clear those placeholders so the
-- ai-diagnostic-worker bootstrap re-embeds each row with the real embedding model at startup.
-- Runs once, before any real embedding can exist, so it only removes placeholders.

UPDATE known_issues
SET embedding = NULL
WHERE embedding IS NOT NULL;
