# AI (OpenRouter, free models)

The LLM sits behind the `LLMProvider` port. `LLMRouter` uses **OpenRouter** when `OPENROUTER_API_KEY` is set, otherwise the built-in keyword simulator, so the app works end to end before a key exists.

## How it works
1. `POST …/prompts` (optional `model`): the user's sentence, the current page JSON and the component list (ids + props schemas) go to OpenRouter's `/chat/completions`.
2. The model must answer **one JSON object** `{"message","operations":[…]}` using the same operations as the editor (`ADD_SECTION`, `UPDATE_PROP`, …). The reply is parsed leniently (code fences allowed) and strictly typed.
3. The operations go through `SchemaPatchEngine` and `PageSchemaValidator` exactly like a hand edit. The model can never add a component outside the registry, exceed prop limits, or carry code; if its proposal is invalid the user gets an `UNSUPPORTED` answer, **nothing is stored**, and no upstream text is echoed.
4. Valid changes become a normal version (kind `PROMPT`); `prompt_runs` records `provider` and `model`.

## Free models only
`GET /api/v1/ai/status` lists the models offered. A model is offered **and accepted** only if OpenRouter lists it with prompt price `0` and completion price `0`, text-only output, ≥ 16k context, and the id is `…:free` or `openrouter/free`; safety/guard/audio models are excluded. Any other id is rejected with `400 MODEL_NOT_ALLOWED`, so nobody can spend credits even if the key has some. On 2026-10-02 the live list had 17 such models (verified against the real endpoint). Restrict further with `OPENROUTER_MODEL_ALLOWLIST=id1,id2`.
* **Auto** (default): tries `openrouter/free` first, then the largest-context free models, up to `OPENROUTER_MAX_ATTEMPTS` (4); a model that fails is skipped for 5 minutes. Network errors, timeouts, 429, 5xx, empty or unusable output move on to the next model; a rejected key or no credit (401/402/403) stops immediately.
* **A specific model**: only that model is tried (no silent substitution).
* **Simulator**: `mock` in the picker or `LLM_PROVIDER=mock`.

## Configuration (environment)
| Variable | Default | Meaning |
| --- | --- | --- |
| `OPENROUTER_API_KEY` | empty | key; empty = simulator. Set via `scripts/set-openrouter-key.sh` (typed silently) or in `.run/public/public.env` |
| `LLM_PROVIDER` | `auto` | `auto` / `mock` / `openrouter` |
| `AI_DAILY_LIMIT_PER_USER` | 50 | real-AI prompts per user per 24 h (the free quota is shared by everyone using the key); simulator is exempt |
| `OPENROUTER_MAX_ATTEMPTS`, `OPENROUTER_TIMEOUT_SECONDS`, `OPENROUTER_MODEL_ALLOWLIST`, `OPENROUTER_REFERER`, `OPENROUTER_BASE_URL` | 4, 45, empty, empty, official | tuning |
The key is only read from configuration, sent only in the `Authorization` header, never logged and never returned (the status endpoint reports only `configured: true/false`).

## Data boundary
With a key set, **the user's sentence, the current page JSON and the component list are sent to OpenRouter and to whichever free model provider answers**. Free providers may log or train on prompts: do not put secrets or personal data in pages. The UI says so under the composer. Without a key nothing leaves the server.

## Limits you will hit
OpenRouter free models have their own rate limits (about 20 requests/min and a daily cap that is much lower without account credit); heavy shared use of one key will exhaust it, in which case users see "AI chưa thể xử lý yêu cầu" and can switch model or use the simulator. Quality varies a lot between free models.

## Verification status
Tested with a local stub of OpenRouter (success, fenced JSON, failover over 429/garbage, all-fail, invalid proposals, model allowlist, daily limit) and against the **real** OpenRouter without a key (live model list parsing; invalid key → fatal error). **A real completion with a valid key has not been run** (`OR_LIVE=1 OR_LIVE_KEY=… ./gradlew test --tests '*OpenRouterLiveTests'` does it).
