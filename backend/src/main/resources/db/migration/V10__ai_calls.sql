-- One row per upstream model call (including failed attempts of an "auto" fail-over chain): the source of AI usage accounting.
-- Token counts and cost are copied from the provider's response (OpenRouter `usage`); NULL means the provider did not report
-- them (e.g. HTTP error, timeout), never an estimate. prompt_id has no FK on purpose: the row is written outside the prompt's
-- transaction so spent tokens stay recorded even when the edit itself is rolled back (revision conflict, invalid proposal).
CREATE TABLE ai_calls (
    id UUID PRIMARY KEY,
    prompt_id UUID,
    workspace_id UUID NOT NULL,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model VARCHAR(160) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    http_status INTEGER,
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    total_tokens INTEGER,
    cost_usd NUMERIC(18, 10),
    latency_ms INTEGER NOT NULL,
    generation_id VARCHAR(160),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_calls_outcome_check CHECK (outcome IN ('OK', 'BAD_OUTPUT', 'ERROR')),
    CONSTRAINT ai_calls_tokens_check CHECK (coalesce(prompt_tokens, 0) >= 0 AND coalesce(completion_tokens, 0) >= 0 AND coalesce(total_tokens, 0) >= 0),
    CONSTRAINT ai_calls_cost_check CHECK (coalesce(cost_usd, 0) >= 0)
);
CREATE INDEX ai_calls_created_idx ON ai_calls (created_at DESC);
CREATE INDEX ai_calls_user_idx ON ai_calls (user_id, created_at DESC);
CREATE INDEX ai_calls_workspace_idx ON ai_calls (workspace_id, created_at DESC);
CREATE INDEX ai_calls_prompt_idx ON ai_calls (prompt_id);
