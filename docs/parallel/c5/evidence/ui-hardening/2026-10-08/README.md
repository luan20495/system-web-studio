# C5 — UI hardening evidence (2026-10-08)

Base `integration/v2 @ e310b6a16156` merged into `agent/c5-web`. **Frontend only**: no backend file, no contract file and no organization URL was added (`git diff e310b6a16156 HEAD -- backend docs/contracts` is empty; `CAPABILITIES` in `features/admin/organization.ts` still has 12 × NOT_READY, 0 × READY).

| Folder | What |
|---|---|
| `unit/` | `initials-tests.txt` (the initials cases incl. "App Creator (Demo)" → AC, "Tenant Admin (Demo)" → TA, emoji / empty / symbol / bracket / Cyrillic / CJK), `organization-tests.txt` (tree at scale, 10 000-member fallback, C0's depth tests) |
| `harness/` | `org-hardening.out.txt` (69 checks), `org.out.txt` (89 checks), `metrics.json`, screenshots of the 2 000-unit tree, depth 10 / 60, 10 000 employees, tree / dialogs / directory at 1440, 768, 390 |
| `real-browser/` | `dialog-check-results.json` (42 checks, **real browser + real stack**, nothing mocked) and screenshots of "Tạo công ty" and the Admin organization / employees screens at 1440, 768, 390 |

## Classes (what each number is)
- **unit**: pure logic, no browser. **harness**: real Chromium on an in-page fake transport, GENERATED fixtures (never real data): timings are this machine's *frontend* render / interaction, **not a backend measurement and not a scale E2E**.
- **real-browser** (`scripts/ui-dialog-check.mjs`, `scripts/ui-audit.mjs`): the real portals against a real stack (integration ae0432f, unpatched). The organization contract does not exist yet, so no real run of org mutations is possible (E2E-ORG01 = WAITING_FOR_C1).

## Measured (harness, this machine)
| Fixture | Measure | Result |
|---|---|---|
| 2 000 generated units (4-ary tree) | rows in the DOM at first paint (large trees start collapsed to the roots) | 5 |
| | data commit (React Profiler) | 14.1 ms |
| | "Mở rộng tất cả": 2 000 rows rendered, click → painted | 399.5 ms |
| | select one row, click → painted | 30.5 ms |
| | …Profiler actual render vs the cost of rendering the whole tree (memoised rows) | 3.9 ms vs 148 ms |
| | "Thu gọn", click → painted | 28.5 ms |
| | `buildTree + flattenTree` (unit test) | 5.8 ms |
| depth 60 chain | every level rendered, aria-level = real level, no page / tree horizontal overflow, indent capped at 10 levels + a "C<n>" tag, End / Home keys | PASS |
| 20 000-deep chain (unit) | no stack overflow (iterative builders) | PASS |
| 10 000 generated employees (paged by the fake transport) | rows in the DOM | 20 (initial and after a search) |
| | 12 quick keystrokes → requests (debounce 250 ms) | 1 |
| | page switch / status filter, click → painted | 21.5 ms / 14 ms |
| | worst keystroke → frame while the list is loaded | 18.5 ms |
| 10 000 members through the client-side fallback | member-list fetches for a search + 4 page switches | 1 (cached) |
| | page switch, click → painted | 14 – 24 ms |
| | search over 10 000 (unit) / page switch (unit) | 15.5 ms / 0.1 ms |

**Virtualization:** not implemented, not needed at these sizes. Recommendation: virtualize the tree when more than ~5 000 rows are visible at once (expand-all of 2 000 rows already costs ~0.4 s) — the flat, memoised row list makes this a drop-in change; the directory is paged, so it never needs it.

## Accessibility notes (what was checked and fixed)
- **Person picker** (Tạo công ty → quản trị viên đầu tiên): combobox with `aria-controls` / `aria-activedescendant`; ArrowDown / ArrowUp move (no wrap), Enter chooses, Escape clears the search, Tab leaves (the listbox is out of the tab order — Chrome made the scroll container focusable); `aria-selected` follows the active option; a polite live region announces the result count / "Không có người phù hợp"; the chosen card has a named "Bỏ chọn" button.
- **Dialogs** (shared `Modal`): focus moves in, Tab / Shift+Tab are trapped (14 presses each way never leave), the page behind is scroll-locked, Escape closes, focus returns to the opener.
- **Errors**: invalid fields are `aria-invalid` and `aria-describedby` their error text (Tạo công ty, unit and type dialogs, create account); the text is `role=alert`.
- **Icon-only buttons** have an accessible name; every visible control has a label.
- **Builder page tree** (Studio): the drag handle and ↑ ↓ buttons were siblings of the treeitem (axe *critical* `aria-required-children`); the row `<li>` is now the treeitem and owns them → 0 critical.
- axe (wcag2a/aa, critical + serious): organization, directory, picker, dialogs and the real Create Company dialog — 0.
- **Known limit**: the Studio builder's own page title is a `role=heading` element (no `<h1>` tag); the audit's tag counter lists it as "no h1".
