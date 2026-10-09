# XWEB brand guideline

Implementation-oriented guide to the ONE XWEB brand used by Platform, Admin and Studio. Everything here is in `packages/ui`: one token foundation, one logo family and one set of backgrounds and banners. Written by C5-S3, 2026-10-09. Evidence for each claim: `tests/builder/brand.test.tsx` (unit), `tests/browser/ui-brand.spec.mjs` (HARNESS, real Chromium, no backend) and `packages/ui/brand/contrast.mjs` (measured contrast).

## 1. Brand direction

XWEB is internal enterprise software for building, running and governing business applications. It should feel **enterprise, modern, clean, modular, trustworthy, precise, calm, technical and premium**.

- **Colour:** one confident cobalt blue (`#1d5bd8`) on quiet slate neutrals. A teal accent (`#9ae6df` in the mark, `#0e7c86` as text) is used sparingly. There is no purple, no neon and no rainbow gradient.
- **Shape:** monoline geometry with rounded joins. Tiles are 8/32 rounded squares. Nothing is ornamental.
- **Surfaces:** flat, with hairline borders and soft shadows. A background can carry a faint 24 px grid (the "modular" cue) or one soft glow, never both at full strength.
- **Avoid:** heavy gradients, glassmorphism, gaming or crypto styling, stock imagery, decorative effects that cost readability.

## 2. Logo usage

The mark is two chevrons meeting at a gap, reading as an **X** (from XWEB) and as two modules joining (platform and builder). It is placed on a cobalt tile. The wordmark XWEB is drawn in the same monoline stroke, and its X repeats the two chevrons.

| Member | File (canonical SVG) | React | Use |
|---|---|---|---|
| A horizontal logo | `packages/ui/brand/xweb-logo-light.svg` | `<BrandLogo/>` | headers, the auth card, documents |
| B square mark | `xweb-mark.svg` | `<BrandMark size={32}/>` | app icon, avatars of the product, banners |
| C light-background version | `xweb-logo-light.svg` (dark ink `#0f172a`) | `<BrandLogo/>` on a light page | |
| D dark-background version | `xweb-logo-dark.svg` (ink `#f5f7fa`, tile `#2a66e0`) | `<BrandLogo/>` inside a dark scope (automatic) | Studio, dark sidebar |
| E monochrome | `xweb-logo-mono.svg`, `xweb-mark-mono.svg` (`currentColor`, glyph knocked out) | `<BrandLogo mono/>`, `<BrandMark mono/>` | print, one-colour stamps, forced colours |
| F favicon | `xweb-mark-small.svg` -> `apps/*/app/icon.svg`, `apple-icon.png` | Next.js file convention | browser tab, home screen |
| G small size (16 / 24 px) | `xweb-mark-small.svg` (stroke 4, wider gap) | `<BrandMark size={16|24}/>` picks it automatically (size <= 24) | |

- The React components draw exactly the geometry of the SVG files (a unit test compares the path data). Colours come from tokens (`--color-brand-tile`, `--color-brand-glyph`, `--color-brand-tile-accent`, `--color-brand-ink`), so a single `<BrandLogo/>` is correct on a light page and inside `.studio`, `.sidebar.dark`, `.modal`, `.drawer` or `[data-theme=dark]`.
- `BrandLogo` is one image with the accessible name "Xweb". `BrandMark` is decorative (`aria-hidden`) unless it gets a `title`.
- In product text the name is written **Xweb** (`BRAND.product`). The wordmark artwork is set in capitals as **XWEB**.

## 3. Clear space and minimum size

- **Clear space:** keep at least half the mark's height (16 units on the 32 grid) free on every side of the logo. In the sidebar that is the `.sideBrand` padding.
- **Minimum size:** the horizontal logo is at least 20 px high (it is 22 px in the sidebar and 28 px on the auth card). The mark is at least 16 px. At 16 and 24 px the small-size geometry is used. Below 16 px, use nothing.

## 4. Incorrect logo usage

Do not:
- recolour the tile with a portal colour, or use one logo per portal. There is one logo;
- stretch or skew it (keep the 136:32 and 1:1 ratios; the components set width from height);
- add effects (shadow, glow, outline, gradient) or put the logo on a busy photo;
- rebuild the wordmark in a font. The letters are paths;
- place the coloured logo on cobalt or on mid-tone backgrounds. Use the mono version instead;
- put the mark inside a sentence as a letter "X";
- use the old diamond `logoMark` (removed) or the legacy names "AI Software Factory", "Company Builder Studio" or "Admin Console". The text-guard ratchet counts the legacy names.

## 5. Colour palette

| Role | Light | Dark |
|---|---|---|
| primary / hover / active | `#1d5bd8` / `#174bb5` / `#123c92` | `#6ea0ff` / `#8db4ff` / `#bcd2fb` |
| primary-soft (washes, selected backgrounds) | `#ebf2fe` | `#13213d` |
| secondary | `#344054` | `#c4cbd6` |
| accent | `#0e7c86` | `#9ae6df` |
| brand tile / mark accent | `#1d5bd8` / `#9ae6df` | `#2a66e0` / `#9ae6df` |
| success / warning / danger / info | `#067647` `#93370d` `#b42318` `#175cd3` on their `-bg` | `#6ce9a6` `#fedf89` `#ff9a92` `#93c5fd` on deep tints |

## 6. Light / dark tokens

Two layers, both in `packages/ui/src/styles/factory.css`:

1. **Semantic `--color-*`** (use these in new code). Light values sit on `:root`. Dark values sit on `.studio, .modal, .drawer, .sidebar.dark, [data-theme=dark]`:
   `--color-primary(-hover|-active|-ink|-soft)`, `--color-secondary`, `--color-accent`, `--color-bg`, `--color-bg-subtle`, `--color-surface`, `--color-surface-raised`, `--color-border`, `--color-border-strong`, `--color-text`, `--color-text-secondary`, `--color-text-muted`, `--color-text-inverse`, `--color-link`, `--color-success|warning|danger|info(-bg)`, `--color-focus`, `--color-selected`, `--color-selected-ink`, `--color-selected-line`, `--color-hover`, `--color-disabled-bg|-ink`, `--color-brand-*`, `--color-pattern`, `--color-glow`.
2. **Legacy names** (still used by existing rules; do not add new uses): `--f-*` (light shell), `--bg --panel --panel2 --line --text --muted --dk-*` (dark, `globals.css`), `--ui-*`, `--sp-*`, `--f-r*`. Each one has the same value as its semantic twin, and `brand.test.tsx` fails if one drifts. They are resolved on `:root`, so they do **not** switch inside a dark scope. Only `--color-*` does.

The dark set was designed, not inverted:
- Surfaces get lighter as they rise: bg `#0b0d10`, then subtle `#0f1115`, surface `#11141a`, raised `#161a21`.
- Primary text uses a lighter cobalt.
- Selection is navy (`#1c3260`) with white text.
- Status colours are pastel on deep tinted surfaces.
- Control borders are `#687385` (3.85:1).

## 7. Typography

System fonts only, with no web-font request (a unit test checks this): `--font-sans: Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif` (Inter only if installed locally) and `--font-mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`.

| Token (`font:` shorthand) | Size / line-height / weight | Use |
|---|---|---|
| `--text-h1` | 24 / 1.25 / 600, tracking `-.01em` | page title (one per page) |
| `--text-h2` | 18 / 1.35 / 600 | dialog title, section |
| `--text-h3` | 16 / 1.4 / 600 | card title |
| `--text-h4` | 14 / 1.45 / 600 | group label |
| `--text-body-lg` | 16 / 1.55 / 400 | banner body, lead |
| `--text-body` | 14 / 1.5 / 400 | default UI text |
| `--text-body-sm` | 13 / 1.5 / 400 | helper text, dense tables |
| `--text-label` | 13 / 1.4 / 600 | form labels, eyebrows (eyebrows add `--tracking-caps` and uppercase) |
| `--text-caption` | 12 / 1.4 / 400 | meta, timestamps |
| `--text-code` | 13 / 1.55 mono | ids, code |

The type is readable at 360 px. Banner titles step down to 20 px under 600 px and wrap with `overflow-wrap:anywhere`.

## 8. Spacing

`--space-0..16` = **2 4 8 12 16 20 24 32 40 48 64 px** (`--space-N` = N x 4 px, plus `--space-0` = 2 px). The older `--sp-1..8` are the same values.

## 9. Radius

`--radius-sm` 6 px (chips, small controls), `--radius-md` 8 px (buttons, inputs), `--radius-lg` 10 px (cards; = `--f-r`), `--radius-xl` 16 px (banners, auth card), `--radius-pill` 999 px (only for pills, badges and toggles).

## 10. Shadows

| Token | Light | Dark | Use |
|---|---|---|---|
| `--shadow-sm` | hairline (= `--f-shadow`) | `0 1px 2px rgba(0,0,0,.4)` | cards |
| `--shadow-md` | `0 4px 12px` at 8 % | deeper | popovers, menus |
| `--shadow-lg` | `0 16px 40px` at 16 % | `0 22px 60px` at 28 % (= `--shadow`) | dialogs, floating panels |

Motion: `--motion-fast` 120 ms and `--motion-base` 180 ms with `--motion-ease`. `prefers-reduced-motion` still disables transitions.

## 11. Backgrounds

CSS only (gradients, no raster, no request), themed by tokens, in `packages/ui/src/styles/ui.css`:

| Class | Composition | Where |
|---|---|---|
| `.xp-bg-auth` | soft cobalt glow at the top, plus a 24 px grid that fades out after 440 px, on `--color-bg` | login, activation, no-access (`AuthPages.tsx`), onboarding banner |
| `.xp-bg-dashboard` | `--color-bg-subtle` fading to `--color-bg` over 240 px | dashboards, landing pages |
| `.xp-bg-hero` | primary-soft to surface diagonal, plus one corner glow | intro banner, home hero |
| `.xp-bg-pattern` | 24 px grid on the surface | documentation and demo banners, empty canvases |

The grid is 4.5 to 5 % opacity: it adds structure without becoming noise, and text contrast is measured against the worst gradient stop (`ui-brand.spec.mjs`).

## 12. Banner patterns

`<Banner variant="intro|docs|onboarding" eyebrow title actions media level>` from `@xweb/ui`:

- **Layout:** a text column (the safe area, 300 px to 62 ch, never covered) and a media column of 160 x 120 px pushed to the end.
- **Media** is decorative: it is `aria-hidden`, defaults to the XWEB mark and is dropped below 600 px. Never put information in it.
- **Desktop:** two columns. **Mobile:** one column with a 20 px padding, the title at 20 px and actions that wrap.
- **Light / dark:** automatic through the tokens.
- **Heading:** `h2` by default, so the page keeps its single `h1`. Pass `level` when needed.

Typical uses: product intro on a portal home (`intro`), a documentation or demo callout (`docs`), first-run or onboarding (`onboarding`).

## 13. Portal identity

There is one logo, one cobalt, one type scale and one spacing system. A portal is told apart only by:

- **Words:** `BrandLockup` prints the logo plus `BRAND.context[portal]` ("Platform", "Quản trị công ty", "Studio") in caption caps.
- **Emphasis:**
  - Platform shows operational, business-wide pages on the light shell.
  - Admin shows management and governance pages on the same light shell.
  - Studio is the creation and build tool, so its editor is the dark scope, and it shares the same cobalt for selection, focus and links.

All three sidebars use the dark navigation rail with the same lockup.

## 14. Accessibility

Measured with `node packages/ui/brand/contrast.mjs`. **56 of 56 pairs pass**: 28 light and 28 dark.

| Pair | Light | Dark |
|---|---|---|
| text-primary on background / surface | 16.51 / 17.85 | 17.87 / 16.94 |
| text-secondary on background | 9.67 | 11.92 |
| text-muted on surface | 6.47 | 7.78 |
| button text on primary | 5.93 | 7.54 |
| link on background | 5.49 | 10.81 |
| error on error surface | 6.05 | 8.34 |
| warning on warning surface | 7.04 | 12.15 |
| selected state text | 5.27 | 12.54 |
| focus indicator on surface / selected (3:1) | 5.93 / 5.27 | 8.67 / 5.90 |
| control border on surface (3:1) | 3.82 | 3.85 |
| logo glyph on tile (3:1) | 5.93 | 5.15 |

The browser spec also measures the rendered text (banner, auth card, lockup) against its real background, including every gradient stop, at 390 and 1280 px in both themes. It also runs axe with 0 serious and 0 critical findings. The existing guards still apply: `design-tokens.test.ts` (M-026 focus ring, M-027 control borders) and forced-colors fallbacks (the logo switches to `CanvasText` / `Canvas`).

## 15. Component examples

```tsx
import { BrandLogo, BrandMark, BrandLockup, Banner, Button } from "@xweb/ui";

<div className="sideBrand"><BrandLockup portal="studio"/></div>          // sidebar
<div className="authBrand"><BrandLogo height={28}/></div>                // auth card
<Banner variant="intro" eyebrow="Xweb Studio" title="Tạo ứng dụng doanh nghiệp từ mô tả"
        actions={<Button variant="primary">Tạo ứng dụng</Button>}>Mô tả nghiệp vụ, Xweb dựng giao diện…</Banner>
<main className="authPage xp-bg-auth">…</main>                           // background
```

These shared components were checked against the tokens: Button (`.btn` light, `:is(.studio,.modal,.drawer) .btn` dark), Input, Select, Checkbox and Radio (`accent-color: var(--f-accent)`), Tabs, Card, Dialog, Table, NavLink and Sidebar (active = primary-soft in light, navy in the dark rail), Pill, Toast (inverse surface), StateView / LoadGate and spinner (primary). They all read the cobalt through the tokens, so no component code changed. The harness page `ui-brand.html` shows them in light and dark.

## 16. Asset locations

| What | Where | Size |
|---|---|---|
| canonical SVGs (6) | `packages/ui/brand/*.svg` | 350 to 1,000 B each |
| PNG exports | `packages/ui/brand/png/` (16, 24, 32, 48, 192, 512, apple-touch 180, logo light / dark @2x); sizes in `png/SIZES.txt` | 341 B to 9.9 KB |
| favicons | `apps/{platform,admin,studio}/app/icon.svg` (350 B) + `apple-icon.png` (1.8 KB) | |
| exporter | `node packages/ui/brand/export-png.mjs` (Chrome, rewrites the PNGs and the favicon copies) | |
| contrast evidence | `node packages/ui/brand/contrast.mjs` | |
| tokens | `packages/ui/src/styles/factory.css` (top) | |
| logo / banner / background CSS | `packages/ui/src/styles/ui.css` (end) | |
| React | `packages/ui/src/Brand.tsx`, `packages/ui/src/Banner.tsx` | |

## 17. Developer usage

- **Colours:** use `var(--color-*)` and nothing else. Never write a hex in a rule (the ui.css guard rejects it). If a role is missing, add a semantic token in BOTH theme blocks and a pair in `contrast.mjs`.
- **Dark scope:** inside `.studio`, `.modal`, `.drawer`, `.sidebar.dark` or `[data-theme=dark]`, the semantic tokens switch automatically. The legacy `--f-*` do not.
- **Spacing and radius:** use `--space-*` / `--radius-*`. Do not add one-off values.
- **Breakpoints:** use only the existing ones (900 / 760 / 600). The breakpoint ratchet counts them. Prefer logical properties (the RTL ratchet caps physical declarations at 97).
- **Logo artwork changes:** to change the logo, edit the SVG, mirror the path data in `Brand.tsx`, run `export-png.mjs`, then run `npm run test:unit` (`brand.test.tsx`).
- **Checking visuals:** `node tests/browser/build-harness.mjs`, then `node tests/browser/harness-server.mjs run -- node tests/browser/ui-brand.spec.mjs`. Before and after a change, compare `css-snapshot.spec.mjs` with `SNAP_BASE` and classify every changed screen.

## 18. Do / don't

| Do | Don't |
|---|---|
| one `BrandLockup` per sidebar, the portal named in words | a different logo or colour per portal |
| `--color-primary` for the one main action per view | several primary buttons, or a primary used as decoration |
| a subtle background (`.xp-bg-*`) behind content | a background that competes with the text, or raster hero images |
| measure contrast (script and spec) after changing a token | trust that it "looks readable" |
| the mono logo on photos and coloured fills | the coloured logo on cobalt or on mid-tone backgrounds |
| Vietnamese UI copy, "Xweb" in sentences | legacy product names, or an English UI string next to the logo |

**Known limits (see the C5-S3 report):**
- The Admin and Platform consoles are light-only today. `[data-theme=dark]` is a ready hook (tokens, auth card, banners, logo) but there is no user theme switch yet.
- The selected radio card inside the dark release dialog still uses the light tint. This predates the brand work, and only its hue changed.
