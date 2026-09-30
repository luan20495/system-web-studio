# System Web Studio

Production-shaped **frontend-only** AI Web Studio for internal company use.

The current repository intentionally uses dummy/mock data, but the UI is already connected through a typed API layer. To connect a real backend later, switch the API mode and implement the documented endpoints — the UI does not need to be rewritten.

## Product UX

The main workspace intentionally stays minimal:

- **Left:** AI prompt / conversation
- **Right:** live website preview
- **Settings:** project, auth, domain, source and deployment configuration
- **History:** Git-style version history
- **Publish:** private/public release flow
- Desktop / tablet / mobile preview

This follows the product principle: **prompt → preview → publish**, with technical controls progressively disclosed only when needed.

## Stack

- Next.js
- React
- TypeScript
- App Router
- Responsive CSS
- Typed domain models
- API client abstraction
- Mock adapter for frontend-only development

## Run locally

```bash
npm install
cp .env.example .env.local
npm run dev
```

Open:

```text
http://localhost:3000
```

## API modes

### Mock mode — current default

```env
NEXT_PUBLIC_API_MODE=mock
```

All screens work without a backend.

### HTTP mode — future backend

```env
NEXT_PUBLIC_API_MODE=http
NEXT_PUBLIC_API_BASE_URL=https://your-api.example.com
```

The frontend will then call the backend through `lib/api-client.ts`.

## Backend-ready endpoints

The expected API contract is documented in:

```text
docs/API_CONTRACT.md
```

Core endpoints:

- `GET /v1/studio`
- `POST /v1/projects/:projectId/prompts`
- `PUT /v1/projects/:projectId`
- `POST /v1/projects/:projectId/publish`

## Project structure

```text
app/
  layout.tsx
  page.tsx
  globals.css

components/
  StudioShell.tsx

lib/
  api-client.ts
  mock-data.ts
  types.ts

docs/
  API_CONTRACT.md
```

## Architecture direction

**Hybrid Schema-Driven + Source Code**

1. Reuse registered components first.
2. Generate custom code only when a registry component cannot satisfy the prompt.
3. Render preview from structured page state/schema.
4. Store versions in Git.
5. Run a security gate before publish.
6. Deploy static websites cheaply and dynamic workloads only when required.

## Current scope

Frontend-only, but the demo interactions are functional with local/mock state:

- project switcher and create project
- settings save
- version history + restore
- undo / redo
- publish private/public mock flow
- direct product-card edit/delete
- working preview navigation and CTAs
- working lead form mock submit
- desktop auto-layout for the Studio shell
- localStorage persistence across refreshes

Only the **real backend services** and **real AI/LLM orchestration** are intentionally left for later integration. The frontend keeps those behind adapters so they can be replaced without redesigning the UI.
