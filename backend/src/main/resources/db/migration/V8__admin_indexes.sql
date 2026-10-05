-- Admin console: last-login lookups and organisation-wide audit filtering stay index-backed.
CREATE INDEX audit_events_actor_action_idx ON audit_events (actor_id, action, created_at DESC);
CREATE INDEX audit_events_created_idx ON audit_events (created_at DESC);
CREATE INDEX deployments_project_created_idx ON deployments (project_id, created_at DESC);
CREATE INDEX prompt_runs_created_idx ON prompt_runs (created_at DESC);
