# ADR 0009 — Runtime plane: immutable static artifacts in object storage behind a gateway
Status: **proposed** (2026-10-02) — design only, not implemented. Increment 7.1.

## Context
`MockDeployProvider` serves nothing; the UI correctly says "Demo deployment". The spec asks for a first real target of
"static artifact → object storage → CDN/Nginx → public/private gateway" and *not* one container per simple site.

## Decision
1. **Artifact.** Publishing a `PAGE_SCHEMA` version renders it with the **same fixed renderer** used by the preview
   (`lib/schema-preview.ts`, data-only input — no generated code runs), in a small Node *render worker* (a separate process consuming
   a RabbitMQ queue, so the TypeScript renderer is not re-implemented in Kotlin and cannot drift). Output: `index.html`, the referenced
   project images copied as files (`asset://id` resolved to relative paths), a manifest (`files`, sizes, SHA-256) and the policy/security
   check results. The artifact is content-addressed: `artifacts/<project>/<sha256>/…` in a dedicated MinIO bucket, write-once.
   For `STATIC_APP` the build plane produces the same artifact format (ADR 0010).
2. **Deploy = pointer switch.** A deployment records `artifact_id`; RUNNING means "the site's current pointer is this artifact".
   Rollback = switch the pointer to an earlier artifact (instant, audited). Old artifacts are kept per retention policy.
3. **Gateway.** One Nginx (or Caddy) instance in front of MinIO (read-only service account, bucket not public):
   * hostname `<slug>.<sites-domain>` → current artifact prefix (looked up from a small map file or an internal lookup endpoint,
     regenerated on every pointer switch; no per-site container);
   * security headers on every response: strict CSP (`default-src 'none'; img-src 'self' data:; style-src 'self' 'unsafe-inline';
     script-src 'none'` for page-schema sites — they contain no scripts), `X-Content-Type-Options`, `Referrer-Policy`, HSTS;
   * **private sites** (`site_visibility = PRIVATE`): gateway `auth_request` to the API, which checks the company SSO session (OIDC)
     and project/workspace membership; public sites are served without auth;
   * TLS and public exposure through the existing dedicated Cloudflare tunnel (wildcard hostname) — never the shared tunnel.
4. **Domains.** Sites use a registrable domain *different from the Studio's* so a site can never read Studio cookies; previews of
   generated code (later) use yet another domain.
5. **Not in 7.1:** contact-form submissions (the component already renders "not connected"; a form-collection endpoint is a separate
   feature), custom domains (data model ready, issuance later), CDN beyond Cloudflare's, analytics.

## Alternatives considered
* One container per site — rejected (spec; cost; attack surface).
* Public MinIO bucket — rejected (no headers, no private sites, bucket listing risks).
* Rendering in the browser and uploading — rejected (the client is untrusted).
* Re-implementing the renderer in Kotlin — rejected (two renderers drift; the preview must equal the published site).

## Acceptance criteria for 7.1
Published site reachable on its hostname with the exact preview markup; private site requires SSO + membership (tested both ways);
rollback switches content without a rebuild; artifacts immutable and hash-verified; CSP and headers asserted in E2E; a deleted
asset fails the policy check (as today); the UI drops the "Demo deployment" label only when this provider is active.
