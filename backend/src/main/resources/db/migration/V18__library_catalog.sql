-- Stage F: template and block catalog metadata, template review workflow, safe-render previews, usage counts.

ALTER TABLE templates ADD COLUMN category VARCHAR(40) NOT NULL DEFAULT 'general';
ALTER TABLE templates ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
-- lifecycle: PRIVATE (author) → SUBMITTED (automated checks) → REVIEW (admin) → APPROVED (company library) | back to PRIVATE; ARCHIVED.
-- visibility/status stay as the access columns (APPROVED ⇔ visibility COMPANY, ARCHIVED ⇔ status ARCHIVED).
ALTER TABLE templates ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'PRIVATE';
ALTER TABLE templates ADD COLUMN usage_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE templates ADD COLUMN preview_key VARCHAR(300);
ALTER TABLE templates ADD COLUMN preview_status VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE templates ADD COLUMN submitted_at TIMESTAMPTZ;
ALTER TABLE templates ADD COLUMN reviewed_by UUID REFERENCES users(id);
ALTER TABLE templates ADD COLUMN reviewed_at TIMESTAMPTZ;
ALTER TABLE templates ADD COLUMN review_comment VARCHAR(1000);
UPDATE templates SET review_status = CASE WHEN status = 'ARCHIVED' THEN 'ARCHIVED' WHEN visibility = 'COMPANY' THEN 'APPROVED' ELSE 'PRIVATE' END;
ALTER TABLE templates ADD CONSTRAINT templates_review_status_check CHECK (review_status IN ('PRIVATE', 'SUBMITTED', 'REVIEW', 'APPROVED', 'ARCHIVED'));
ALTER TABLE templates ADD CONSTRAINT templates_category_check CHECK (category ~ '^[a-z0-9-]{1,40}$');
ALTER TABLE templates ADD CONSTRAINT templates_preview_status_check CHECK (preview_status IN ('NONE', 'READY', 'FAILED', 'UNAVAILABLE'));
CREATE INDEX templates_review_idx ON templates (review_status, updated_at DESC);

-- append-only history of a template's workflow (submissions, automated checks, decisions)
CREATE TABLE template_reviews (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL REFERENCES templates(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    actor_id UUID REFERENCES users(id),
    decision VARCHAR(16) NOT NULL,
    comment VARCHAR(1000) NOT NULL DEFAULT '',
    checks JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT template_reviews_decision_check CHECK (decision IN ('SUBMITTED', 'CHECKS_PASSED', 'CHECKS_FAILED', 'APPROVED', 'REJECTED', 'WITHDRAWN', 'ARCHIVED', 'RESTORED'))
);
CREATE INDEX template_reviews_template_idx ON template_reviews (template_id, created_at);

ALTER TABLE component_packages ADD COLUMN category VARCHAR(40) NOT NULL DEFAULT 'general';
ALTER TABLE component_packages ADD COLUMN tags TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE component_packages ADD COLUMN usage_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE component_packages ADD COLUMN preview_key VARCHAR(300);
ALTER TABLE component_packages ADD COLUMN preview_status VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE component_packages ADD CONSTRAINT component_packages_category_check CHECK (category ~ '^[a-z0-9-]{1,40}$');
ALTER TABLE component_packages ADD CONSTRAINT component_packages_preview_status_check CHECK (preview_status IN ('NONE', 'READY', 'FAILED', 'UNAVAILABLE'));
