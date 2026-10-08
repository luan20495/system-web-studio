import { ROWS as BASE } from "./ledger-data.mjs";
const rows = BASE.map((r) => [...r]);
const by = (id) => rows.find((r) => r[0] === id);
const note = (id, t) => { const r = by(id); r[13] = [r[13], t].filter(Boolean).join(" · "); };
const status = (id, s) => { by(id)[14] = s; };
const moveSrc = (from, to, ids) => { const f = by(from), t = by(to); for (const i of ids) { f[6] = f[6].filter((x) => x !== i); t[6].push(i); } };
// ---- R phase-2 mis-merge corrections
moveSrc("M-010", "M-021", ["S1-011"]); moveSrc("M-010", "M-023", ["S3-035"]);
note("M-010", "C-01: split (S1-011 -> M-021, S3-035 -> M-023); only S2-004 remains here");
by("M-011")[6] = ["S3-004"]; by("M-011")[5] = "Studio AI/Code workspace on a phone hides Website/Phiên bản/Tệp/Chia sẻ/Cài đặt (and Lịch sử/Thư viện/IDE/Máy chủ) with no replacement menu";
rows.push(["M-104","P2","C5","Studio AI/Code mode","<=767px","Studio AI mode on a phone: 3-row top bar 141 px + prompt 492 px leave the preview 211 px (25% of 844); no pane switch in the real UI",["S3-022"],"responsive.css:98-155 pane-tab rules target legacy markup only; ProjectWorkspace layout","S1","none","N (replica in S3 audit: re-measure)","pane switch (chat / preview) on <=767 reusing the builder phone-switch pattern","responsive matrix + harness","split from M-011 (C-02)"]);
rows.push(["M-105","P2","C5","Platform+Admin","5 Admin dialogs","Admin dialogs: fieldset.stack = UA 2px groove border, legend 16px/400, dialog title a bare 24px h2 (no ModalHeader); .stack defined nowhere",[],"features/admin dialogs + factory.css (.stack undefined)","S3 (CSS) + S2","none","N (harness)","define .stack/legend/ModalHeader usage for Admin dialogs","harness screenshot + axe","split from M-012 (C-03)"]);
moveSrc("M-012", "M-105", ["S3-007"]);
by("M-012")[8] = "S3 (CSS) + S1 (DataSourcesPanel.tsx is a Studio file) + S2 (Admin host)"; note("M-012", "O-01: panel markup is S1's file");
rows.push(["M-106","P2","C5","Studio","workflow / action editors","Workflow editor targets/branches list raw step ids (end, generated ids); jargon",["S1-052"],"builder/WorkflowEditor.tsx:37,69,106,116-117","S1","C4 contract unchanged","N (harness)","show step titles, hide ids","builder.spec",""]);
by("M-061")[6] = by("M-061")[6].filter((x) => x !== "S1-052");
moveSrc("M-061", "M-064", ["S2-031"]); note("M-064", "C-05: also takes S2-031 (settings card keys, stale copy)");
rows.push(["M-107","P2","C5","all","tests/tsconfig shims","9 shim files and 22-23 relative ../packages/... imports in S1/S2 files (3 spellings of one import): unit runner compiles to CommonJS/node10 so @xweb/* cannot load",["R-023"],"scripts/test-unit.mjs:2 + tests/tsconfig.json","S4 (one repo-wide pass, LAST, after S1/S2/S3 merged)","C0 owns scripts/test-unit.mjs","N","single import spelling; do not break the unit runner","unit + all builds","split from M-072 (C-07); executes last"]);
by("M-072")[6] = by("M-072")[6].filter((x) => x !== "R-023");
note("M-067", "C-06: also covers the ladders/filters/persistence half of raw R-011 (its act() half is in M-020)");
by("M-042")[8] = "S1 (both preview halves: lib/schema-preview.ts is a C5 hot file) + C2 review"; by("M-042")[2] = "C5"; note("M-042", "C-11");
by("M-086")[5] = "No forced-colors support; Inter declared but never loaded; font weights unsupported by the system stack";
note("M-099", "C-09: grab-bag; every item is tracked in the wave plan, do not treat as one fix");
note("M-080", "C-08: grab-bag; items handled individually");
note("M-018", "D-01: runtime secret delete is also in M-019 (S1 file CodePanels.tsx:139: owner S1)");
note("M-028", "D-02/D-03: radio cards also M-081; PersonPicker aria-expanded also M-085");
note("M-041", "D-05: shares a root with M-056: 21 of 72 useLoad results never read .error");
note("M-053", "D-06: AdminApp split shared with M-066");
for (const id of ["M-060","M-061","M-062","M-063"]) note(id, "O-03: S3 supplies the label library + guard; S1/S2 apply it in their own files");
note("M-075", "O-04: packages/api-client/core.ts is touched by M-074/M-075/M-092/M-097: single editor S3");
// ---- severity decisions by C5-L
by("M-074")[1] = "P2"; note("M-074", "raised: raw R-002 and R-003 are both P2");
by("M-014")[1] = "P2"; note("M-014", "lowered P1->P2 for consistency with M-026/M-027 (same WCAG AA rule, narrow-viewport only)");
note("M-076", "DailyBars half is latent (backend zero-fills days, AdminAiUsageController.kt:64); the JSON.parse half stays P2");
note("M-103", "R2-026 was P2; kept as RESEARCH, blocks any tenant-theme work");
// ---- R2 corrections
const m51 = by("M-051"); m51[1] = "P3"; m51[2] = "C5"; m51[9] = "C2 confirmation (non-blocking, HF-C2-03): server validates path and builds the redirect from the configured sites origin"; m51[10] = "N"; m51[11] = "client: https only, no credentials, valid path; host pinned only if C0 provides the sites origin (NOT 'same-origin': the redirect goes to the sites origin)"; note("M-051", "R2: P2->P3, server half is closed in source");
const m52 = by("M-052"); m52[1] = "P3"; note("M-052", "R2: UX-only, server authorises; guard needs a single-helper allow-list and features/admin + TENANT_ADMIN in scope");
const m88 = by("M-088"); m88[8] = "S1 + S2"; m88[5] += "; RuntimeDrawer secret cleared even on a failed save; retained result.activation and ActivatePage token/again fields"; m88[11] = "clear after use; keep the activation link only until copied/confirmed (reconcile with M-007); fix path join"; m88[12] = "harness DOM / input-value assertions (unit is not enough)";
by("M-049")[11] = "one-shot store (NOT history.state: the Next router owns it); a ?prompt= URL only pre-fills the composer; never POST on load"; by("M-049")[12] = "harness: zero POST on load";
by("M-050")[11] = "show a credential-free URL + separately masked username/token; guard the null token (prints 'null' today); do NOT invent http.extraHeader";
by("M-089")[11] = "renderer escaping fuzz test is the real regression guard (C5-only); CSP sha256 for the canvas script is a C0 handoff"; 
note("M-093", "R2: split (a) failed /auth/config -> error state: do now; (b) 'absent permissions = deny' blocked on HF-C1-05, verify on a real stack");
by("M-094")[11] = "interim C5: drop the displayName heuristic and map SELF_REVIEW (403) to a Vietnamese message; server canApprove flag stays a C0-gated handoff";
note("M-009", "R2 thought P2 plausible; C5-R verified from source that a non-member SYSTEM_ADMIN holds only {TENANT_MANAGE, TENANT_MEMBERS} so forProject answers 404 (Permission.kt:109-111, AccessService.kt:85-111): P1 stands. Use Me.businessAccess to explain, not hide by guess");
note("M-039", "STATIC-BE (R2): domain/customDomain cannot be cleared (hostname regex rejects ''); null = unchanged; '' clears description/deploymentTarget. Question stays with C2");
note("M-040", "STATIC-BE (R2): /ai/status?workspaceId= narrows the model list by access rules. Question stays with C2");
by("M-064")[1] = "P3"; note("M-064", "R2: not a contradiction: both minimums (6 at sign-up, 8 at activation) are intentional in the backend; state the rule per flow, ask C1 about showing 'no username' / '>=4 distinct characters'");
note("M-098", "use portalHref when configured (window.location.origin may be an internal host)");
note("M-091", "also clear sessionStorage keys in logout and the 401 handler");
note("M-092", "retry only before the first stream byte");
note("M-006", "P1 justified only because M-076 shows real throw paths (R2 rated it P3)");
note("M-071", "packages/i18n is 23 lines; run the S3 glossary pass first; phase 0 (provider, locale-aware formatters, ratchet guard) is C5-only; a second locale needs a product decision");
note("M-069", "386 hex includes 30 token definitions (R2 count 356 excludes them)");
// ---- status after S2 integration (merged e27318c, HARNESS)
status("M-007", "C5 PART FIXED (HARNESS, merged e27318c) · C1 re-issue handoff open");
status("M-008", "FIXED (HARNESS, merged e27318c)");
status("M-009", "C5 PART FIXED (HARNESS, merged e27318c) · C1 handoff + real-backend 404 not confirmed");
export const ROWS = rows;

// ---- S4 phase 1 (25 raw): merged into existing roots or added
const addSrc = (id, ids) => { by(id)[6].push(...ids); };
addSrc("M-053", ["S4-001","S4-002","S4-003","S4-006","S4-008"]); note("M-053", "S4 measured: First Load JS Platform/Admin 809.4/230.0 KB (raw/gzip, identical), Studio 912.6/267.6 KB, legacy 1198.0/340.8 KB; login-only code ~54 KB vs 400-517 KB loaded; 0 dynamic chunks; builder 367.9 KB min, @dnd-kit 71% of Studio code (SYNTHETIC build metrics, Next 16 prints no route sizes)");
addSrc("M-012", ["S4-004"]); note("M-012", "S4-004: TenantScreens.tsx:20 pulls 24 KB of features/studio into Platform and Admin");
addSrc("M-003", ["S4-011"]); note("M-003", "S4-011 measured: preview reload after a selection 45 ms at 50 sections, 104 ms at 404, 208 ms at 1004 (HARNESS)");
addSrc("M-046", ["S4-013"]); note("M-046", "S4-013: canvas scroll drops 25/96 frames at 1004 sections, 0 at the contract maximum (HARNESS)");
addSrc("M-097", ["S4-017"]); note("M-097", "S4-017: no cache/abort; the same lists fetched by two screens; session calls start after hydration");
addSrc("M-072", ["S4-031","S4-035"]); note("M-072", "9 specs fall back to port 4000 when HARNESS_URL is unset (S4-031); default CHROME path is Linux-only (S4-035)");
addSrc("M-099", ["S4-033"]); note("M-099", "data-testid coverage: 1 for 142 handlers in AdminApp.tsx, 0 in StudioApp/ProjectWorkspace/AuthPages (S4-033)");
addSrc("M-102", ["S4-036"]);
rows.push(["M-108","P3","HANDOFF","infra","next build / CSP","HTML is no-store because of the per-request CSP nonce (fails Lighthouse back/forward-cache); ~14 KiB legacy-JS polyfills remain (no browserslist floor)",["S4-005","S4-007"],"packages/auth/src/server/csp.ts, apps/*/proxy.ts, layout.tsx await connection(); Next default target","C0 (decision)","C0: keep nonce (security) or hash-based CSP for static shells; declare the browser floor","N","handoff; C5 keeps nonce behaviour","Lighthouse bf-cache + legacy-javascript audits",""]);
rows.push(["M-109","P3","C5","Studio","design · Pages panel","PagesPanel canStep is O(n²) per render (20-32 ms at 1000 sections; beyond the 50-section contract)",["S4-010"],"PagesPanel.tsx:69-70; core/dnd.ts:56-63","S1","none","N (harness)","O(1) first/last rule; unit test equals canStep for all positions","perf-micro + unit",""]);
rows.push(["M-110","P3","C5","Studio","design · tree/canvas handles","Page-tree rows and canvas drag handles are not memoised (85% of selection cost at >=404 sections); the tree unmounts when the rail changes",["S4-012"],"PagesPanel.tsx:68-70, Canvas.tsx:51,62, BuilderWorkspace.tsx leftPanel","S1","none","N (harness)","memo + stable handlers; window above ~200 rows","perf-harness attribution table",""]);
rows.push(["M-111","P3","C5","Admin","organization","Expand-all has no windowing (26,073 DOM nodes for 2,000 units; 200 ms paint); moveTargets rebuilds the whole tree; unitPath per row (O(rows x units))",["S4-014","S4-015","S4-016"],"OrganizationScreens.tsx:70,140-164,230; organizationModel.ts:68,89-105; EmployeesScreens.tsx:88","S2","none (NOT_READY org)","N (harness)","window above ~1000 rows; reuse the tree memo; id->unit Map once","org-hardening PERF + perf-harness + perf-micro",""]);
rows.push(["M-112","P3","C5","Studio","builder CSS","The phone page-scroll mode needs :has() (no fallback)",["S4-020"],"builder.css:194,203; factory.css:144","S1 (builder.css) + S3","none","N","class on <html> set from the builder instead of :has(), or state the floor","Firefox/WebKit run once S4-022 is resolved",""]);
rows.push(["M-113","P3","C5","Studio","published site / preview","Unconditional scroll-behavior:smooth ignores prefers-reduced-motion (renderer output changes: republish note for C2)",["S4-021"],"lib/preview-document.ts:13","S1 (C5 file) + C2 notified","C2: republish note","N","@media (prefers-reduced-motion:no-preference)","page-runtime.spec + CSS assertion",""]);
rows.push(["M-114","P2","TOOLING","tooling","cross-browser","Only Chrome 155 (headless) was ever executed: no Firefox/WebKit/Safari run for the builder (sandboxed srcdoc iframe, dnd-kit pointer events, :has) or login",["S4-022"],"environment: no Playwright Firefox/WebKit installed; adding CI is forbidden by CLAUDE.md","S4 (+ user/C0: installing browsers on the integration machine)","none","N","install playwright firefox/webkit on the integration machine and run portals/builder/org specs, or one manual Safari pass; until then cross-browser is a STATIC report only","same specs under firefox/webkit","never claim Safari/Firefox support from a Chrome run"]);
rows.push(["M-115","P2","TOOLING","tooling","portals.spec","portals.spec hard-coded ports 3001/3002 (would read the live stack by accident)",["S4-030"],"tests/browser/portals.spec.mjs:8","S4","none","N","PORTAL_PLATFORM_PORT / PORTAL_ADMIN_PORT overrides","spec 33/33 x3 on private builds","FIXED"]);
status("M-115", "FIXED (merged b0aff1d, spec 33/33 x3 on private ports)");
rows.push(["M-116","P3","TOOLING","tooling","unit tests","Contract conformance test is silently skipped unless XWEB_CONFORMANCE_DIR is set",["S4-032"],"tests/builder/conformance.test.ts:27,31,36","S4 (C0 file for the runner)","C0 owns scripts/test-unit.mjs","N","loud pending line","unit summary",""]);

// ---- phase-2 integration statuses (merged into agent/c5-web; all browser evidence is HARNESS, NOT REAL BACKEND)
status("M-001", "FIXED (HARNESS, merged e3f74d5; no 'Từ chối' button: the server has no reject endpoint for code changes)");
status("M-002", "FIXED (HARNESS, merged e3f74d5)");
status("M-003", "FIXED (HARNESS + unit golden hash: published output byte-identical, merged e3f74d5)");
status("M-004", "FIXED (HARNESS, merged e3f74d5; streaming not driven)");
status("M-005", "PROPOSAL APPROVED by C5-L: single guided form + 'Nâng cao' wizard, auto-created hidden slot, v1 = direct columns only (custom columns wait for the C3 mapping answer); implementation by S1 pending");
status("M-006", "PARTIAL: ErrorBoundary + error.tsx/global-error.tsx + per-route wrap merged (da6c70f); Builder inline boundary (S1) pending");
status("M-010", "FIXED shared (HARNESS, merged da6c70f); callers whose submit lacks aria-busy must pass dismissible={!busy}: S1/S2 in wave 2");
status("M-013", "FIXED (HARNESS, merged da6c70f; 360/800/900px)");
status("M-014", "FIXED (HARNESS, merged da6c70f; 320/360/390)");
status("M-016", "PARTIAL: shared Toast + confirm/prompt components merged (da6c70f); call-site migration pending (S1/S2 wave 2)");
status("M-022", "FIXED (HARNESS, merged da6c70f)");
status("M-012", "PARTIAL: shared CSS merged (da6c70f, HARNESS); leftover for S1/S2: DataSourcesPanel delete confirm uses 'button primary' (should be danger)");

// ---- integration round 2 (agent/c5-web c9799bb): S1 milestone 1, S3 wave-1 milestone 1, S2 wave 1, S4 wave 1 (HARNESS unless stated)
status("M-015", "FIXED (HARNESS, merged 5e11aca): Tab/Shift+Tab leave the editor; opt-in indent option; Esc-then-Tab");
status("M-005", "FIXED (HARNESS, merged 5e11aca): guided 4-section form, wizard under 'Nâng cao', v1 direct columns; 'Sửa' of an existing connection not done");
status("M-006", "FIXED (HARNESS): boundaries in the three apps (da6c70f) + Builder panels inline (merged 5e11aca)");
status("M-048", "FIXED (merged 5e11aca; behaviour-preserving: StudioApp 393->89 lines, ProjectWorkspace 413->315 + hooks)");
status("M-066", "FIXED (merged c9799bb): AdminApp.tsx 1,197 -> 40 lines, ONE section registry, console context; 360-cell route snapshot taken before the split passes unchanged");
status("M-076", "FIXED (HARNESS, merged c9799bb): safe JSON helpers; DailyBars half was latent");
status("M-054", "FIXED (HARNESS, merged c9799bb)");
status("M-052", "FIXED (HARNESS + guard test, merged c9799bb): single roles helper, guard scans features/admin");
status("M-059", "C5 PART FIXED (HARNESS, merged c9799bb): provider tile reads /admin/ai/providers; backend handoff open");
status("M-064", "FIXED (HARNESS, merged c9799bb); C1 question (no-username / >=4 distinct) unchanged");
status("M-075", "COMPONENT/LIB MERGED (bb5f6e4): errorText/errorParts + code catalog; call-site migration pending (S1/S2 wave 2)");
status("M-074", "FIXED (merged bb5f6e4): X-Request-Id header read, reporter seam");
status("M-092", "FIXED (merged bb5f6e4): CSRF retry once before the first stream byte");
status("M-084", "COMPONENT MERGED (bb5f6e4): States/LoadGate; feature adoption pending");
status("M-079", "COMPONENT MERGED (bb5f6e4)");
status("M-024", "FIXED (HARNESS, merged bb5f6e4): Picker aria-activedescendant");
status("M-028", "WIDGET MERGED (bb5f6e4): Tabs/TabPanel; adoption pending"); status("M-029", "WIDGET MERGED (bb5f6e4): DisclosureRow; adoption pending"); status("M-030", "WIDGET MERGED (bb5f6e4): RadioGroup; adoption pending"); status("M-031", "WIDGET MERGED (bb5f6e4): ReasonButton; adoption pending"); status("M-032", "WIDGET MERGED (bb5f6e4): Pill tones; adoption pending");
status("M-020", "HOOK MERGED (a5dcdc1): useAction; call-site migration pending (S1/S2 wave 2)");
status("M-097", "PARTIAL: useLoad keyed cache + abort merged (a5dcdc1) and session reset wired (bb5f6e4); api.* methods do not yet expose signal; tenant switch not covered");
status("M-072", "PARTIAL: spec toolkit + HARNESS_URL required merged (a5dcdc1), all specs migrated; shims (R-023 -> M-107) and untested files remain");
status("M-115", "FIXED (merged b0aff1d)");
rows.push(["M-117","P3","C5","all","global link style","Links inside running text are distinguished only by colour (axe link-in-text-block, WCAG 1.4.1)",["S3-WAVE1"],"global link style in factory.css/globals.css","S3","none","N (harness)","underline links in running text (or another non-colour cue) via a token","axe in ui-widgets harness + admin screens","found by S3 while building the widgets harness; new, not in the phase-1 audits"]);

// ---- integration round 3 (agent/c5-web dbef6fa): S3 wave 1 milestone 2 (HARNESS/unit)
status("M-025", "COMPONENTS MERGED (dbef6fa): SkipLink + useMain (route focus, conditional tab stop); shell wiring pending (S1/S2)");
status("M-026", "FIXED (HARNESS + computed-ratio unit tests, merged dbef6fa): light-theme focus ring >=3:1");
status("M-027", "FIXED for light controls (merged dbef6fa): --f-control-border 3.4-3.8:1; dark-surface borders not changed (ledger scope)");
status("M-085", "FIXED (merged dbef6fa): scroll-padding-bottom for sticky footers");
status("M-117", "FIXED (merged dbef6fa): underline links in running text via --ui-link-line");
status("M-033", "FIXED (HARNESS, merged dbef6fa): provider row wraps at 390 (was 77px)");
status("M-087", "FIXED (merged dbef6fa): dvh with vh fallback, content max-width 1360 at >=1280, breakpoints ratcheted (consolidation of odd thresholds deferred to item 8)");
status("M-086", "FIXED (HARNESS forcedColors emulation, merged dbef6fa)");
status("M-070", "PARTIAL: undefined tokens --danger/--warn defined (merged dbef6fa); duplicate selector cleanup pending (S3 item 8)");
