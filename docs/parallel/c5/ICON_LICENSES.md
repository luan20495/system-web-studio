# Icons and logos in the design system (C5, 2026-10-07)

| What | Package | License | Where | Notes |
|---|---|---|---|---|
| Functional icons (Server, KeyRound, ShieldCheck, Settings2, Save, Activity, Zap, Plus, Pencil, Trash2, Power, Download, Check, Chevron…, Plug, Sparkles, Cpu, CircleCheck/Alert/Slash, Lock, Bot, X) | `lucide-react` | ISC | `packages/ui/src/icons.ts` (named re-exports, tree-shaken) | the only icon source for UI functions |
| Provider brand marks: OpenRouter, Anthropic, Google Gemini | `simple-icons` | CC0-1.0 (the data/paths); the brands' trademarks remain their owners' | `packages/ui/src/ProviderLogo.tsx` | rendered as a single-colour mark (`currentColor`) in a neutral tile, only to identify the provider the administrator is configuring; no brand colour, no endorsement wording, no modification of the mark |
| **OpenAI** | – | – | generic Lucide `Sparkles` + the name | Simple Icons no longer ships an OpenAI mark (removed at the owner's request); C5 does not copy it from elsewhere. Add the official asset only after reading OpenAI's brand guidelines. |
| Local (Ollama and others), OpenAI-compatible | – | – | generic Lucide `Server`, `Plug` | not tied to one brand |

Rules kept: no icon, logo or font is fetched from the internet at run time (the harness asserts that every request stays on the page's own origin); no hot-linking; icons are inline SVG and decorative (`aria-hidden`), the provider's NAME is always in the DOM next to it.
Dependencies added to `packages/ui/package.json`: `lucide-react`, `simple-icons` (root `package-lock.json` regenerated: C0 takes it with the C5 import, `npm ci` must pass).
