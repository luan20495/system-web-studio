# Pilot plan — internal pilot of the AI Software Factory

Goal: learn whether employees can build and run useful internal software safely, and what it costs, before any production decision.
Automated tests passing is **not** a reason to call the system production-ready; the pilot supplies the evidence tests cannot.

## Scope
* **Users:** 5–20 internal employees (mix of builders and 2–3 reviewers/admins), invited by an admin (public sign-up stays off; SSO when the company IdP is configured).
* **Duration:** 1–2 weeks, with a go/no-go review at the end.
* **Environment:** the existing pilot (`studio.toolsmcp.uk`, websites; this Mac). Source apps and server apps run only on the local full stack (or a Linux runner once provided) with named test users — they are **not** exposed on the public pilot.
* **AI:** free OpenRouter models first (needs `OPENROUTER_API_KEY` — not yet provided); no paid model until its price, a hard budget and an explicit admin enable are in place.

## Use cases (one per track)
1. **Static company website** — a 3–5 page site (home, about, contact form) from a template; published publicly on `sites.toolsmcp.uk`; form submissions reviewed by the owner.
2. **AI-generated source app** — an employee describes a small React tool; AI proposes changes, sandbox build, review, publish privately to the team.
3. **Internal dashboard / tool** — a Dashboard on sample or API data, and one Internal tool or Workflow with its own database (local full stack / Linux runner only).

## What to measure (all available in the Admin Console unless noted)
| Signal | Where | Pilot threshold to proceed |
|---|---|---|
| AI requests, tokens, cost by user/workspace/model | Admin → AI Control, Quản trị AI | cost per active user known; no budget alert ignored |
| Build count, failures, CPU time, queue wait | Admin → Build & lưu trữ, Sức khỏe | build failure rate understood (< 20 % not caused by platform bugs) |
| Publish success rate, rollbacks | Admin → Ứng dụng, audit | ≥ 95 % publishes succeed |
| Runtime errors, restarts, failed deployments | Admin → Sức khỏe (Server runtime), app logs | no unexplained crash loops |
| Component/block/template reuse | Studio → Templates/Components (usage counts), `prompt_runs.reuse_sources` | reuse visible; ≥ 1 approved block/template used by others |
| CPU / RAM / storage | Admin → Chi phí (storage, CPU), host monitoring | profile memory within the host's budget |
| Backup success, restore drill | Admin → Sao lưu | every day OK; drill passes weekly |
| Security findings, rejected builds, quota hits | Admin → Bảo mật, Build & lưu trữ (rejections with reasons) | no unresolved HIGH/CRITICAL |
| User feedback | 5-question survey at the end of week 1 and 2 (usefulness, speed, errors, trust, would use again) | median ≥ 3.5/5 on usefulness and trust |

## Roles during the pilot
* **Owner (you):** invites users, picks the three use cases, decides go/no-go.
* **Admin:** enables models/budgets, reviews templates/blocks/packages, watches alerts daily.
* **Reviewer:** approves merge requests of source apps (review policy REVIEW_REQUIRED for the pilot workspace).

## Entry checklist
- [ ] `OPENROUTER_API_KEY` configured; one free model enabled; daily request/token quota set; a hard money budget set even if free
- [ ] Pilot users created (or SSO + SCIM mapping) and added to a pilot workspace; merge policy REVIEW_REQUIRED
- [ ] Off-host backup target configured (`OFFSITE_S3_*`) or the pilot explicitly accepts same-machine backups
- [ ] Linux runner available **or** server-app use cases limited to the local stack
- [ ] Alerts watched daily; weekly restore drill checked

## Exit criteria
Go for a larger pilot only if: the thresholds above hold, no security incident, backups verified, and users would keep using it. Production additionally needs the
P0 items in the readiness report (off-site backup, Linux isolated runtime if server apps are in scope, real company SSO, a production security review).
