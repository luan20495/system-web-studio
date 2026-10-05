# @company/app-sdk 1.0.0

Shared runtime for source-code apps. Apps should use it instead of re-implementing these concerns.

| API | Purpose |
| --- | --- |
| `<FactoryApp>` + `useRuntimeConfig()` | loads `__factory/config.json` served by the gateway: app id/name, environment (preview/production), visibility, version, flags, apiBase |
| `useUser()` | signed-in user of a PRIVATE app (company SSO via the gateway) — `null` for public apps |
| `useFlag(name)` | feature flags from the runtime config |
| `assetUrl(path)` | URL of a file in `public/` under any base path |
| `logger.info/warn/error(msg, fields)` | structured console logging (no network) |
| `createApiClient(base)` / `useApi()` | JSON client with timeout + `ApiError`; base comes from the runtime gateway (server apps / connectors), never from credentials in code |

Apps run in a sandboxed origin (no cookies/localStorage); keep state in React.
