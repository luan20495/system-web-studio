-- Email lookup for member management and SSO; never used on its own as an authorization decision.
ALTER TABLE users ADD COLUMN email VARCHAR(254);
ALTER TABLE users ADD COLUMN auth_source VARCHAR(16) NOT NULL DEFAULT 'LOCAL';
ALTER TABLE users ADD CONSTRAINT users_auth_source_check CHECK (auth_source IN ('LOCAL', 'OIDC'));
CREATE UNIQUE INDEX users_email_lower_idx ON users (lower(email)) WHERE email IS NOT NULL;

-- (issuer, subject) is the only stable identity of an external login.
CREATE TABLE external_identities (
    issuer VARCHAR(300) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES users(id),
    email VARCHAR(254),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login_at TIMESTAMPTZ,
    PRIMARY KEY (issuer, subject)
);
CREATE INDEX external_identities_user_idx ON external_identities(user_id);

-- Cleanup jobs scan by age.
CREATE INDEX idempotency_keys_created_idx ON idempotency_keys(created_at);
CREATE INDEX assets_pending_idx ON assets(created_at) WHERE status IN ('PENDING', 'DELETED');
CREATE INDEX deployments_finished_idx ON deployments(finished_at) WHERE finished_at IS NOT NULL;
