-- Stage G: website form submissions and custom domains.

-- Submissions of ContactForm sections on published sites. Personal data: visible to project editors only, deleted after the retention
-- period (Admin → Settings → retention.form-submission-days). The visitor IP is stored only as a salted hash (rate limiting, abuse review).
CREATE TABLE form_submissions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    deployment_id UUID REFERENCES deployments(id) ON DELETE SET NULL,
    form_id VARCHAR(64) NOT NULL,
    page_id VARCHAR(64) NOT NULL DEFAULT 'home',
    data JSONB NOT NULL,
    ip_hash VARCHAR(64) NOT NULL,
    user_agent VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX form_submissions_project_idx ON form_submissions (project_id, created_at DESC);
CREATE INDEX form_submissions_created_idx ON form_submissions (created_at);

-- Custom domains: ownership proven by a DNS TXT record (_hbl-verify.<host> = token); no DNS credentials are ever stored.
-- TLS is terminated in front of the gateway (CDN/tunnel); tls_status is what a real HTTPS handshake to the host showed at last check.
CREATE TABLE site_domains (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    hostname VARCHAR(253) NOT NULL UNIQUE,
    verification_token VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    tls_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    last_error VARCHAR(300),
    verified_at TIMESTAMPTZ,
    last_checked_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT site_domains_status_check CHECK (status IN ('PENDING', 'VERIFIED', 'FAILED')),
    CONSTRAINT site_domains_tls_check CHECK (tls_status IN ('UNKNOWN', 'PENDING', 'ACTIVE', 'ERROR'))
);
CREATE INDEX site_domains_project_idx ON site_domains (project_id);
