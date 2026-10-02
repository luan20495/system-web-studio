-- Phase 6 (ADR 0007): multiple AI providers.

-- Admin decision per model id ("provider:model" or an OpenRouter id). Paid-provider models are disabled unless a row enables them;
-- OpenRouter free models are enabled unless a row disables them.
CREATE TABLE ai_model_policies (
    model_id VARCHAR(200) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Explicit, admin-maintained prices (USD per million tokens). Rows are never updated: a price change is a new row with a later
-- effective_from, so the cost of every past call can be reproduced. Nothing is pre-filled: without a row, cost stays unknown.
CREATE TABLE ai_model_pricing (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    model_id VARCHAR(200) NOT NULL,
    input_usd_per_mtok NUMERIC(14, 6) NOT NULL,
    output_usd_per_mtok NUMERIC(14, 6) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note VARCHAR(200) NOT NULL DEFAULT '',
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_model_pricing_prices_check CHECK (input_usd_per_mtok >= 0 AND output_usd_per_mtok >= 0)
);
CREATE INDEX ai_model_pricing_model_idx ON ai_model_pricing (model_id, effective_from DESC);

-- Where a call's cost came from: PROVIDER (reported in the response, e.g. OpenRouter) or CATALOG (tokens × the pricing row in force).
ALTER TABLE ai_calls ADD COLUMN cost_source VARCHAR(16);
ALTER TABLE ai_calls ADD COLUMN pricing_id UUID REFERENCES ai_model_pricing(id);
ALTER TABLE ai_calls ADD COLUMN request_id VARCHAR(64);
ALTER TABLE ai_calls ADD CONSTRAINT ai_calls_cost_source_check CHECK (cost_source IS NULL OR cost_source IN ('PROVIDER', 'CATALOG'));
UPDATE ai_calls SET cost_source = 'PROVIDER' WHERE cost_usd IS NOT NULL;
