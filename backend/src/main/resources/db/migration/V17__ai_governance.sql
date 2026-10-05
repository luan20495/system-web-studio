-- Stage E: AI governance (ADR 0014).

-- Model access rules below the global enable flag (ai_model_policies = org level). A rule can only RESTRICT: the effective decision for a
-- user is "enabled globally AND not denied at org, workspace, the user's workspace role, or the user" (most restrictive wins). model_id is
-- an exact id, '*' (every non-simulator model) or 'paid:*' (every model of a paid provider).
CREATE TABLE ai_model_access (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    scope_id VARCHAR(64) NOT NULL,            -- 'org' | workspace uuid | '<workspace uuid>:<role>' | user uuid
    model_id VARCHAR(200) NOT NULL,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_model_access_scope_check CHECK (scope_type IN ('ORG', 'WORKSPACE', 'ROLE', 'USER')),
    CONSTRAINT ai_model_access_unique UNIQUE (scope_type, scope_id, model_id)
);

-- Money budgets over provider-reported or catalog-priced cost (ai_calls.cost_usd). Calls whose cost is unknown are counted separately and
-- never priced by guess. A non-USD budget needs an explicit, admin-entered conversion rate (usd_per_unit); none is ever assumed.
CREATE TABLE ai_budgets (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    scope_id VARCHAR(64) NOT NULL,            -- 'org' | workspace uuid | user uuid | project uuid
    period VARCHAR(8) NOT NULL,
    amount NUMERIC(14, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    usd_per_unit NUMERIC(18, 10) NOT NULL DEFAULT 1,
    soft_percent INTEGER NOT NULL DEFAULT 80,
    hard BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_budgets_scope_check CHECK (scope_type IN ('ORG', 'WORKSPACE', 'USER', 'PROJECT')),
    CONSTRAINT ai_budgets_period_check CHECK (period IN ('DAILY', 'MONTHLY')),
    CONSTRAINT ai_budgets_amount_check CHECK (amount > 0 AND usd_per_unit > 0 AND soft_percent BETWEEN 1 AND 100),
    CONSTRAINT ai_budgets_currency_check CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ai_budgets_unique UNIQUE (scope_type, scope_id, period)
);

-- Admin console alerts (budget thresholds, provider failures, quota refusals). dedupe_key keeps one open alert per condition and period.
CREATE TABLE admin_alerts (
    id UUID PRIMARY KEY,
    kind VARCHAR(40) NOT NULL,
    severity VARCHAR(8) NOT NULL,
    scope_type VARCHAR(16),
    scope_id VARCHAR(64),
    message VARCHAR(500) NOT NULL,
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    dedupe_key VARCHAR(200) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    acknowledged_by UUID REFERENCES users(id),
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT admin_alerts_severity_check CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);
CREATE INDEX admin_alerts_open_idx ON admin_alerts (acknowledged_at, created_at DESC);

-- Every tool the model asked for during a prompt: authorized and executed by the server with the user's permissions, result size only.
CREATE TABLE ai_tool_calls (
    id UUID PRIMARY KEY,
    prompt_id UUID NOT NULL,
    user_id UUID NOT NULL,
    project_id UUID NOT NULL,
    tool VARCHAR(40) NOT NULL,
    arguments JSONB NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    result_chars INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_tool_calls_outcome_check CHECK (outcome IN ('OK', 'DENIED', 'INVALID', 'ERROR'))
);
CREATE INDEX ai_tool_calls_prompt_idx ON ai_tool_calls (prompt_id);

-- Where the content of a prompt's result came from (6.7): {"components":[...], "templates":[...], "blocks":[...], "generated":n}
ALTER TABLE prompt_runs ADD COLUMN reuse_sources JSONB;
-- streamed prompts can end cancelled or timed out with partial output
ALTER TABLE prompt_runs DROP CONSTRAINT prompt_runs_status_check;
ALTER TABLE prompt_runs ADD CONSTRAINT prompt_runs_status_check CHECK (status IN ('UPDATED', 'NO_CHANGE', 'UNSUPPORTED', 'FAILED', 'CANCELLED', 'TIMEOUT'));
ALTER TABLE ai_calls DROP CONSTRAINT ai_calls_outcome_check;
ALTER TABLE ai_calls ADD CONSTRAINT ai_calls_outcome_check CHECK (outcome IN ('OK', 'BAD_OUTPUT', 'ERROR', 'CANCELLED', 'TIMEOUT'));
