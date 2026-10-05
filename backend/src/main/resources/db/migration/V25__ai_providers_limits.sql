-- Core product: AI is configured on the web, not through environment variables.
-- Provider keys are stored encrypted (AES-GCM, SECRETS_MASTER_KEY) and are write-only: no API returns them.
CREATE TABLE ai_providers (
    id UUID PRIMARY KEY,
    slug VARCHAR(40) NOT NULL UNIQUE,
    kind VARCHAR(24) NOT NULL,
    name VARCHAR(80) NOT NULL,
    base_url VARCHAR(300) NOT NULL DEFAULT '',
    api_key_enc TEXT,
    models JSONB NOT NULL DEFAULT '[]'::jsonb,
    default_model VARCHAR(100),
    paid BOOLEAN NOT NULL DEFAULT TRUE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_providers_kind_check CHECK (kind IN ('OPENROUTER','OPENAI','ANTHROPIC','GEMINI','OPENAI_COMPATIBLE','LOCAL')),
    CONSTRAINT ai_providers_slug_check CHECK (slug ~ '^[a-z0-9][a-z0-9-]{1,38}$')
);

-- Limits that differ from the defaults (defaults live in system_settings). NULL = inherit.
-- USER: requests/tokens per day and paid budget per month for that person. WORKSPACE: the same per person in the workspace, plus
-- tokens/month and paid budget/month for the whole workspace. PROJECT: requests per day and tokens per month for that app.
CREATE TABLE ai_limit_overrides (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(12) NOT NULL,
    scope_id VARCHAR(64) NOT NULL,
    requests_per_day INTEGER,
    tokens_per_day BIGINT,
    tokens_per_month BIGINT,
    paid_budget_month NUMERIC(14,4),
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_limit_overrides_scope_check CHECK (scope_type IN ('USER','WORKSPACE','PROJECT')),
    CONSTRAINT ai_limit_overrides_nonneg CHECK (coalesce(requests_per_day,0) >= 0 AND coalesce(tokens_per_day,0) >= 0 AND coalesce(tokens_per_month,0) >= 0 AND coalesce(paid_budget_month,0) >= 0),
    UNIQUE (scope_type, scope_id)
);
