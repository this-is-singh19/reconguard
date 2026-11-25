-- Create resolution_suggestions table for AI classification output
CREATE TABLE IF NOT EXISTS resolution_suggestions (
                                        suggestion_id VARCHAR(64) PRIMARY KEY,
                                        transaction_id VARCHAR(64) NOT NULL,
                                        tenant_id VARCHAR(64) NOT NULL,
                                        matched_issue_id VARCHAR(64) REFERENCES known_issues(issue_id),
                                        confidence_score NUMERIC(5,4) NOT NULL,
                                        status VARCHAR(32) NOT NULL, -- AUTO_RESOLVED, AUTO_CLASSIFIED, NEEDS_REVIEW
                                        reasoning TEXT NOT NULL,
                                        suggested_action TEXT NOT NULL,
                                        created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

                                        CONSTRAINT fk_resolution_transaction
                                            FOREIGN KEY (tenant_id, transaction_id)
                                            REFERENCES transactions(tenant_id, transaction_id)
                                            ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_resolution_suggestions_tenant_tx
    ON resolution_suggestions (tenant_id, transaction_id);

CREATE INDEX IF NOT EXISTS idx_resolution_suggestions_status
    ON resolution_suggestions (status);