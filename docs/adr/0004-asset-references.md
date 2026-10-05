# ADR 0004 — Images in components as asset://{id}
Status: accepted (2026-10-02)

Hero and ProductGrid items gained an optional `image` prop (`format: "asset"`, migration V9, additive to props schema 1.0.0). Values
must be `asset://<uuid>`; raw URLs are rejected (422 SCHEMA_INVALID). `SchemaCommitService` rejects references to assets that are not
READY in the same project (400 ASSET_NOT_FOUND) — prompts, edits and restores alike — and the publish policy check fails if a referenced
asset was deleted later. The renderer resolves references only through the project's own asset list (short-lived signed URLs from the
API), so MinIO is never addressed by user-supplied URLs.
