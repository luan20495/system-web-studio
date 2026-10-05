-- Core product: accounts created by an admin are activated by the user through a one-time link (no temporary passwords).
-- activated_at: when the account first had a usable password. Existing and self-created accounts count as activated now.
ALTER TABLE users ADD COLUMN activated_at TIMESTAMPTZ DEFAULT now();
UPDATE users SET activated_at = created_at WHERE activated_at IS NOT NULL;

-- One-time links (ACTIVATION for a new account, RESET for a password reset). Only the SHA-256 hash of the token is stored.
CREATE TABLE account_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose VARCHAR(12) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_tokens_purpose_check CHECK (purpose IN ('ACTIVATION', 'RESET'))
);
CREATE INDEX account_tokens_user_idx ON account_tokens (user_id, created_at DESC);
