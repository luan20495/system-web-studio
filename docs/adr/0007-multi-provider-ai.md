# ADR 0007 — Multiple AI providers: extend the in-process abstraction (A), keep a gateway (B) possible
Status: accepted (2026-10-02). Written before implementation, as the spec requires; the "Implementation" section was filled in afterwards.

## Context
Today the only external AI is OpenRouter (free models), behind `LLMProvider` → `LLMRouter` → `OpenRouterLLMProvider` → `OpenRouterClient`,
with model policy in `AiService`, usage in `ai_calls` (Phase 4) and budgets in `AiUsageService`. Phase 6 asks for more providers
(OpenAI, Anthropic, Gemini, internal/local models) without breaking OpenRouter, and to decide between:

* **A.** extend the existing provider abstraction in the Spring Boot API;
* **B.** put a LiteLLM gateway (a separate proxy that speaks the OpenAI API to ~100 providers) in front of all models.

## Trade-offs
| | A. In-process providers | B. LiteLLM gateway |
| --- | --- | --- |
| New infrastructure | none | one more service (Python), usually with its own database for keys/spend; must be patched, monitored, backed up |
| Secrets | provider keys in the API's environment only (as today) | keys move into the gateway (its config/DB); the API holds a gateway key; two places to secure |
| Trust boundary | unchanged: model output is untrusted data parsed by `OpenRouterLLMProvider`'s planner and validated by `PageSchemaValidator` | same parsing still needed in the API; the gateway adds a component that sees every prompt and page |
| Usage / cost / budgets | already implemented (`ai_calls`, token budgets); extended with a pricing catalog | the gateway has its own spend tracking and budgets → duplicated or conflicting numbers unless one side is switched off |
| Provider breadth | each wire protocol is code we maintain. Many providers speak the OpenAI chat API (OpenAI, Gemini's OpenAI-compatible endpoint, Ollama, vLLM, LM Studio, LiteLLM itself); Anthropic needs its own Messages API client | very wide, quirks handled upstream |
| Failure modes | one process; a provider outage is one failed HTTP call, recorded as such | an extra hop and an extra single point of failure unless run redundantly |
| Effort now | small: two clients (OpenAI-compatible, Anthropic) + catalog/policy | deploy + configure + integrate + reconcile accounting |
| Lock-in | low | low (OpenAI-compatible API), but operational dependence on the gateway |

## Decision
**A**, with one generic **OpenAI-compatible provider** that can be pointed at any OpenAI-compatible endpoint. That keeps **B** available
later without code changes: a LiteLLM (or other) gateway is just another OpenAI-compatible base URL. Revisit B when the number of
providers or provider-specific features (embeddings, images, tool calling across vendors) makes our own clients the bottleneck.

Rules kept from earlier phases:
* Generated output is still data; nothing executes; the validator is the boundary.
* Keys come only from the environment, are sent only in the provider's auth header, and are never logged, stored or returned.
* **No silent spend:** "auto" keeps meaning *OpenRouter free models*. Paid models are used only when a user picks one explicitly, an
  explicit model is never replaced by another, and every model of a non-OpenRouter provider (paid ones and internal ones alike) is
  **disabled until a system admin enables it**.
  Models must be listed explicitly per provider (no "any model the key can reach").
* **Cost:** provider-reported cost when the provider returns one (OpenRouter); otherwise cost is computed from an explicit,
  admin-maintained **pricing catalog** (`ai_model_pricing`, immutable rows with `effective_from`) and the provider-reported tokens.
  No price is ever built in or guessed; without a catalog row the cost stays unknown. Each call records where its cost came from.
* Provider availability is shown only as "configured" (key + model list present) unless an admin runs a live connection check.

## Implementation (after the fact, 2026-10-02)
* `integration/llm/ChatProviders.kt`: `ChatProvider` interface, `OpenAiCompatibleProvider` (OpenAI with `max_completion_tokens` and no
  temperature; Gemini via its OpenAI-compatible endpoint; `local` for Ollama/vLLM/LM Studio/a gateway, key optional), `AnthropicProvider`
  (Messages API, `x-api-key` + `anthropic-version: 2023-06-01`, cached input tokens counted as input), `AiProviderRegistry`
  (environment-only configuration; ids `provider:model`). OpenRouter is wrapped as a `ChatProvider` inside `ExternalLLMProvider`
  (formerly `OpenRouterLLMProvider`), which keeps the prompt, parsing and validation rules for every provider.
* `AiService`: catalog across providers, `ai_model_policies` (paid models off unless enabled, OpenRouter free models on unless disabled),
  `MODEL_NOT_ALLOWED` for anything not configured, listed and enabled.
* `AiUsageService.price`: PROVIDER or CATALOG cost per call, `pricing_id` and `request_id` stored in `ai_calls` (migration V12).
* Admin: `GET /admin/ai/providers`, `POST /admin/ai/providers/{id}/probe`, `PUT /admin/ai/models/policy`, `GET/POST /admin/ai/pricing`.
* Verified only against local stubs (backend `MultiProviderTests`, browser `e2e/admin-setup-flow.mjs`, `AiConfigWebTests`). **No real OpenAI, Anthropic, Gemini
  or local model has been called**; parameter names follow the providers' public documentation as of 2026-10.
