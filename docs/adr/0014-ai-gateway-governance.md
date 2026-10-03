# ADR 0014: AI gateway governance (model access, money budgets, alerts, streaming, tools, retrieval)
Status: accepted and implemented (2026-10-03). Stage E of the roadmap. Builds on ADR 0007 (multi-provider).

## Context
All AI features (page prompts, code prompts) call providers through one gateway (`ExternalLLMProvider.complete`). Before this stage the only
controls were: a global enable flag per model, a per-user daily request quota and token budgets. A company rollout also needs: who may use
which model, spending limits in money, admin alerts, streaming answers that can be cancelled, server-side tools, and reuse of approved
company assets before free generation.

## Decision
**One gate.** `AiGate.authorize` runs before every model call (also for streams, before the stream starts, so refusals are ordinary HTTP
errors): model enabled globally → access rules → token budgets → money budgets → daily request quota. `AiGate.after` prices and records
every upstream call (outside the business transaction), raises budget alerts and provider-failure alerts.

**Model access (6.2).** `ai_model_access` holds DENY rules at ORG, WORKSPACE, ROLE (workspace role) and USER level; the global enable
flag (`ai_model_policies`) is the org gate. A rule can only restrict, so "most restrictive wins" is simply "any matching deny blocks".
Patterns: an exact id, `paid:*` (every model of a paid provider) and `*` (every real model). The simulator is never restricted. For "auto"
the denied free models are excluded from fail-over. Admin → Quản trị AI lists rules and shows the effective decision per model for a user.

**Money budgets (6.3).** `ai_budgets` per ORG / WORKSPACE / USER / PROJECT, DAILY or MONTHLY, with soft threshold (%) and hard flag.
Spending = provider-reported cost or reported tokens × the admin's immutable price row (ADR 0007); calls without a known cost are counted
and shown, never estimated. A non-USD budget requires an admin-entered `usd_per_unit`; no exchange rate is assumed. A hard budget blocks
PAID models once spent ≥ limit (free models cost nothing and stay usable). A paid model with no price row cannot be checked, so it is refused
(`AI_COST_UNKNOWN`) while a hard budget applies. The check happens before a call; one answer can overshoot by its own cost.

**Alerts (6.4).** `admin_alerts` with a dedupe key (one alert per condition and period): budget soft/hard thresholds, provider refusing
the key or credit (HTTP 401/402/403). Admin → Cảnh báo lists and acknowledges them (audited).

**Streaming (6.5).** `POST …/prompts/stream` and `POST …/code/ai/stream` answer with server-sent events: `start {streamId, deadline}`,
`delta {text}`, `status {text}` (tool use), `result` (same body as the non-streamed endpoint, with provider-reported usage) or `error`.
Providers stream natively (OpenAI-compatible incl. OpenRouter and local servers with `stream_options.include_usage`; Anthropic Messages
events). Cancel: `POST /api/v1/ai/streams/{id}/cancel` (owner only) or client disconnect; deadline: setting `ai.stream-timeout-seconds`.
A blocked read is woken by interrupting the worker only while it is inside the provider call (never during database work). The call is
recorded with outcome CANCELLED / TIMEOUT and any usage reported so far; the prompt run keeps the partial output; nothing is applied.
Responses carry `X-Accel-Buffering: no` so reverse proxies do not buffer. At most 16 concurrent streams per instance (503 `AI_BUSY`).

**Controlled tools (6.6).** The model may answer `{"tool":…,"arguments":…}` up to 4 times per attempt; this works with every provider
(no vendor-specific function calling). `AiToolbox` validates arguments, runs the tool with the prompting user's permissions on the prompt's
own project and records each call in `ai_tool_calls` (arguments without file contents, result size, outcome OK/DENIED/INVALID/ERROR).
Page prompts: `search_component`, `search_template`, `read_project_schema`, `search_connector_metadata`. Code prompts: `search_component`,
`read_project_file`, `propose_file_change` (stages a file that still goes through `CodeChangePolicy`, the build sandbox and review),
`search_connector_metadata`. Tools never run code, SQL or network requests; results go back as delimited data.

**Retrieval priority (6.7).** The system prompt orders reuse: approved registry components → approved company blocks (listed with their
props) → approved templates (tool) → new content. `prompt_runs.reuse_sources` records the verified result: a block counts when an added
section uses its component with ≥ half of its props unchanged; a template counts only if a tool returned it AND the model names it; the
remaining added sections count as generated.

## Status of verification
Everything above is covered by integration tests against a local OpenAI-compatible stub (`AiGovernanceTests`) and an SSE smoke test through
the Next.js proxy. **Real provider calls (6.1) are BLOCKED_EXTERNAL_INPUT**: no OPENROUTER/OPENAI/ANTHROPIC/GEMINI key is configured on this
server, so native streaming formats are implemented from the providers' documented formats but not yet verified against the live APIs.

## Consequences
Streams hold a worker thread each (bounded pool). Money control is only as complete as the price catalog: admins must add a price for each
paid model they enable. Tool calls add round trips (and tokens) — capped at 4 per attempt.
