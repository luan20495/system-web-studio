# AI providers (OpenRouter free models + optional OpenAI, Anthropic, Gemini, internal models)

> Phase 6 (ADR 0007): besides OpenRouter, the API can call **OpenAI**, **Anthropic**, **Google Gemini** (OpenAI-compatible endpoint) and an
> **internal/local OpenAI-compatible server** (Ollama, vLLM, LM Studio, or a gateway such as LiteLLM). Each is configured only through the
> environment: `<P>_API_KEY` (local: optional), `<P>_BASE_URL`, `<P>_MODELS` (explicit comma list) with `<P>` = `OPENAI`, `ANTHROPIC`,
> `GEMINI`, `LOCAL_LLM`. Users see these models as `provider:model`. **Every model of these providers is disabled until a system admin
> enables it** (Admin Console → AI Control → Nhà cung cấp AI); "Tự động" still means OpenRouter free models only, and an explicitly chosen
> model is never replaced. Cost: OpenRouter's reported cost, otherwise reported tokens × the admin-maintained **pricing catalog**
> (USD per million tokens, immutable rows applied from the moment they are added; no built-in prices — without a row the cost is unknown).
> Each call stores `cost_source` (PROVIDER/CATALOG), `pricing_id` and `request_id`. "Kiểm tra kết nối" lists the provider's models (no tokens).
> Tested only against local stubs; no real call to these providers has been made.


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
| `AI_DAILY_TOKEN_LIMIT_PER_USER` | 0 (off) | tokens per user in a rolling 24 h, from provider-reported usage |
| `AI_MONTHLY_TOKEN_LIMIT_PER_WORKSPACE` | 0 (off) | tokens per workspace per calendar month (database time zone) |
| `CLEANUP_AI_CALL_DAYS` | 400 | retention of usage rows (`ai_calls`) |
| `OPENROUTER_MAX_ATTEMPTS`, `OPENROUTER_TIMEOUT_SECONDS`, `OPENROUTER_MODEL_ALLOWLIST`, `OPENROUTER_REFERER`, `OPENROUTER_BASE_URL` | 4, 45, empty, empty, official | tuning |
The key is only read from configuration, sent only in the `Authorization` header, never logged and never returned (the status endpoint reports only `configured: true/false`).

## Usage accounting (tokens, cost)
* Every upstream call is one row in `ai_calls`: user, workspace, project, prompt, model, outcome (`OK`, `BAD_OUTPUT` = answered but unusable, `ERROR` = no answer such as HTTP 429 or a timeout), HTTP status, prompt/completion/total tokens, cost in USD, latency and OpenRouter's generation id. Failed fail-over attempts are rows too, because an answered-but-unusable call can still be billed.
* Tokens and cost are **what OpenRouter reports** in the response `usage` object (requested with `usage.include=true`). When a value is missing it is stored as `NULL` and shown as "không báo", never as an estimate or as 0. Free models report a cost of exactly 0. The admin totals show how many calls had no reported usage.
* The model call runs **outside** the database transaction, and its usage rows are written before the edit is committed, so tokens that were spent stay on record even if the edit is then rejected (for example by a revision conflict). Side effect: the prompt no longer holds a database connection for the (up to 45 s × attempts) model call.
* The simulator makes no upstream call: it has no rows and no usage.
* Budgets (optional, off by default): `AI_DAILY_TOKEN_LIMIT_PER_USER` and `AI_MONTHLY_TOKEN_LIMIT_PER_WORKSPACE` are checked **before** calling the model and answer `429 AI_TOKEN_LIMIT`. The size of the next answer is unknown in advance, so one answer can go a little over the limit. Calls without reported usage count as 0 tokens toward a budget.
* Where to see it: Admin Console → AI Control (totals, daily chart, by model, user, workspace, call log with filters); Studio Home (my tokens in 24 h, 30-day totals); each AI answer in the editor shows its tokens and cost.
* Not implemented: price tables of our own (cost comes only from the provider), invoices or chargeback, alerts when a budget is nearly used up, budgets per model.

## Data boundary
With a key set, **the user's sentence, the current page JSON and the component list are sent to OpenRouter and to whichever free model provider answers**. Free providers may log or train on prompts: do not put secrets or personal data in pages. The UI says so under the composer. Without a key nothing leaves the server.

## Limits you will hit
OpenRouter free models have their own rate limits (about 20 requests/min and a daily cap that is much lower without account credit); heavy shared use of one key will exhaust it, in which case users see "AI chưa thể xử lý yêu cầu" and can switch model or use the simulator. Quality varies a lot between free models.

## Verification status
Tested with a local stub of OpenRouter (success, fenced JSON, failover over 429/garbage, all-fail, invalid proposals, model allowlist, daily limit, usage accounting incl. failed attempts, missing usage, rollback after a concurrent edit, user and workspace token budgets, admin report and call log) and against the **real** OpenRouter without a key (live model list parsing; invalid key → fatal error). **A real completion with a valid key has not been run** (`OR_LIVE=1 OR_LIVE_KEY=… ./gradlew test --tests '*OpenRouterLiveTests'` does it).

## Cấu hình AI trên web (Admin → AI)
Quản trị viên cấu hình AI hoàn toàn trên giao diện, không cần biến môi trường:
* **Nhà cung cấp** — "+ Thêm nhà cung cấp": OpenRouter, OpenAI, Anthropic, Google Gemini, Tương thích OpenAI, AI nội bộ (Local). Khóa kết nối được mã hóa AES-GCM
  (`SECRETS_MASTER_KEY`, tự sinh bởi `scripts/public-up.sh`/`_env.sh`) và **chỉ ghi**: không API nào trả khóa về, không ghi log, không vào nhật ký hoạt động.
  Nhà cung cấp cấu hình bằng biến môi trường hiển thị "Được quản lý bởi hệ thống" và luôn thắng cấu hình trên web cùng id.
  "Kiểm tra kết nối" và "Tải danh sách mô hình" chỉ gọi endpoint liệt kê mô hình / kiểm tra khóa, không tiêu token.
* **Mô hình** — bật/tắt từng mô hình, Miễn phí/Trả phí, nhập giá cho mô hình trả phí, đặt mô hình mặc định. Nhân viên chỉ thấy mô hình đang bật.
* **Hạn mức** — mặc định an toàn: mô hình "Tự động", mô hình trả phí tắt, ngân sách trả phí 0 (chưa cấp, chặn mọi lần dùng mô hình trả phí), 50 lượt AI/người/ngày,
  token không giới hạn. Ghi đè theo người dùng / không gian làm việc / ứng dụng; trang chi tiết người dùng hiển thị hạn mức hiệu lực và nguồn của nó.
  Số 0 luôn hiển thị là "Không giới hạn" (lượt, token) hoặc "Chưa cấp ngân sách" (ngân sách trả phí).
