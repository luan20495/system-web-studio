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

// ---- S4 wave-2 TOOL FINDINGS (state matrix / keyboard walkthrough / matrix; HARNESS, NOT REAL BACKEND; pre-merge base, to be re-run)
rows.push(["M-118","P2","C5","Studio","14 Studio screens · long names","Long project/app names overflow horizontally on 14 Studio screens (+96 px and +434 px)",["S4W2-01"],"Studio screens (cards, lists, headers) without min-width:0 / wrapping; scripts/ui-state-matrix.mjs long-content cells","S1","none","N (harness)","wrap/ellipsis + min-width:0 in the Studio cards, lists and headers","state-matrix long-content cells PASS at 1440/390",""]);
rows.push(["M-119","P2","C5","Platform+Admin+Studio","components · system · settings · studio/new","No empty-state message on 4 screens (components, system, settings, studio/new)",["S4W2-02"],"state matrix empty cells","S2 (admin) + S1 (studio/new)","none","N (harness)","LoadGate empty slot with a real message","state-matrix empty cells PASS",""]);
rows.push(["M-120","P2","C5","Platform","ai/usage","platform/ai/usage crashes with an empty daily series ('Invalid time value')",["S4W2-03"],"usage chart date formatting of an empty/blank day (a path other than DailyBars fixed under M-076); backend zero-fills days but the UI must not crash","S2","none","N (harness)","guard the empty/invalid day in the usage view","admin harness daily=empty on ai/usage",""]);
rows.push(["M-121","P2","C5","Platform","costs","platform/costs shows NaN when empty",["S4W2-04"],"costs KPI arithmetic on missing numbers","S2","none","N (harness)","number guards + 'chưa có dữ liệu'","admin harness empty costs",""]);
rows.push(["M-122","P2","C5","Studio","studio/new","studio/new has no loading indicator and shows nothing when the request fails; admin/identity shows no error on failure",["S4W2-05"],"load ladders without error/loading branches","S1 (studio/new) + S2 (identity)","none","N (harness)","LoadGate / ErrorState","state-matrix error/loading cells PASS",""]);
rows.push(["M-123","P2","C5","Platform","tenants · Tạo công ty","After a SUCCESSFUL 'Tạo công ty' focus falls to <body> (the Escape path restores focus correctly)",["S4W2-06"],"dialog closes on success without restoring focus to the opener / next logical target","S2 (+S3 if Modal)","none","N (harness)","restore focus to the opener or to the new company's heading","ui-keyboard walkthrough step PASS",""]);
rows.push(["M-124","P3","TOOLING","Admin","employees dialogs","18 blank and 18 console-error visits in the 9-width harness matrix (two employee dialogs and similar) are untriaged: harness fixture artefact or real defect?",["S4W2-07"],"scripts/ui-audit-harness.mjs rows","S4 + S2","none","N","triage: classify each as artefact or defect; fix the defects","re-run the matrix",""]);
rows.push(["M-125","P3","C5","Studio","site screen · project rail","9 visits with targets < 24 px on the Studio site screen; 4 'covered' controls on the builder rail in Studio project screens",["S4W2-08","S4W2-09"],"SitePanels.tsx controls; builder rail covered by a sticky/overlay element in the matrix","S1","none","N (harness)","raise targets; find the covering element","matrix: 0 small / 0 covered",""]);
note("M-075", "S4 state matrix (HARNESS, pre-adoption): 52 screens show raw 'java.lang.NullPointerException …' text on an injected 500 and 19 show raw 'Access Denied' on a 403; closes when S1/S2 adopt errorText on all screens");
note("M-056", "S4 state matrix: also admin/identity shows no error when its request fails (see M-122)");

// ---- integration round 4 (agent/c5-web 9ea2e39): S3 wave 1 milestone 3 (HARNESS/unit) + S4 wave 2 tooling
status("M-060", "GUARD + GLOSSARY MERGED (9ea2e39): ratchet-only denylist guards (internal-constant 15, internal-term 72, legacy-name 11, …); copy fixes pending (S1/S2 lower the ceilings as they fix)");
status("M-061", "LIBRARY MERGED (9ea2e39): 25 typed enum label maps with a neutral 'Khác' fallback; adoption pending (S1/S2)");
status("M-062", "GLOSSARY + SINGLE ROLE TABLE MERGED (9ea2e39); 11 product-owner decisions D-1..D-11 listed in S3-glossary.md; adoption pending");
status("M-063", "BRAND constant + ratchet MERGED (9ea2e39); legacy names still in strings (ceiling 11)");
status("M-068", "VOCABULARY + <Button> + dark skin MERGED (9ea2e39); call-site migration of .button/.smallButton/.bx-btn pending (S1/S2; ratcheted: 73/72/56)");
status("M-069", "PARTIAL (9ea2e39): 107 value-identical token replacements (0 of 34 css-snapshot screens changed), hex literals 344 -> 234 uses; radius/shadow/font-size tokens, dark palette merge and builder.css hex (S1) remain");
status("M-070", "PARTIAL (9ea2e39): 22 provably dead declarations removed (pixel-identical), 26 -> 19 conflicting duplicate selectors; 99 split-rule duplicates remain");
note("M-087", "odd breakpoint thresholds (700/720/768/767/520/1100/1023/1279) NOT consolidated: moving switch points is a visual decision; ratchet prevents growth");
note("M-021", "harness fidelity: 9 harnesses did not load ui.css (fixed 9ea2e39); new harnesses must load ui.css after factory.css (guard test)");

// ---- integration round 5 (agent/c5-web 071dfb4): S2 wave 2 milestone 1, S4 wave 3, S3 wave 2 milestone A (HARNESS unless stated)
status("M-017", "S2 PART FIXED (HARNESS, merged eb38a2d): all 21 native dialogs in Admin are confirm()/prompt() + guard test; S1 part pending");
status("M-018", "FIXED (HARNESS, merged eb38a2d): role changes by explicit Lưu (+confirm for admin roles); confirms for provider off, connector off, package deny, template share, retention cleanup");
status("M-025", "ADMIN PART FIXED (HARNESS, merged eb38a2d); Studio shell wiring pending (S1)");
status("M-055", "FIXED (HARNESS, merged eb38a2d): real 404 for unknown addresses");
status("M-056", "FIXED (HARNESS, merged eb38a2d): error + retry on failed secondary loads, LoadGate on 9 ladders");
status("M-057", "FIXED (HARNESS, merged eb38a2d): reason shown as text, no dead control; not wired (employee `active` is membership state)");
status("M-058", "C5 PART FIXED (HARNESS, merged eb38a2d): company list search + pager; C1 handoff open");
status("M-119", "ADMIN/PLATFORM PART FIXED (HARNESS, merged eb38a2d); studio/new pending (S1)");
status("M-120", "FIXED (HARNESS, merged eb38a2d + S4 matrix: ai/usage no longer crashes)");
status("M-121", "FIXED (HARNESS, merged eb38a2d)");
status("M-122", "ADMIN PART FIXED (HARNESS, merged eb38a2d); studio/new pending (S1)");
status("M-123", "FIXED (HARNESS, merged eb38a2d): focus goes to the new company's h1 via useMain");
status("M-020", "ADMIN PART FIXED (HARNESS, merged eb38a2d): no act() copy remains in Admin; Studio part pending (S1)");
status("M-088", "ADMIN PART FIXED (HARNESS, merged eb38a2d); RuntimeDrawer secret (S1) pending");
status("M-124", "CLOSED (merged 071dfb4): triaged as a HARNESS ARTEFACT (system-admin persona is refused by the company screen: dialog states not reachable); the engine records a forbidden-state refusal as 'skipped'");
status("M-053", "PARTIAL (merged 071dfb4): console is a lazy chunk behind the login: Platform/Admin first load 866.6 -> 549.2 KB raw (-36.6%), Studio 989.0 -> 562.4 KB (-43.1%) (lab build output; React/Next runtime ~443 KB of it); deeper splitting inside Studio/Admin pending");
status("M-105", "CSS PATTERN MERGED (c51c705): .stack/legend/ModalHeader; markup adoption in the 5 Admin dialogs pending (S2)");
status("M-071", "PHASE 0 MERGED (c51c705): vi-only provider, cached locale-aware formatters with identical output (0 Intl objects per 1000 cells), lang/dir from the locale; negotiation + catalogues + 2nd locale NOT started (need product decision)");
status("M-103", "RTL RATCHET MERGED (c51c705): 97 physical CSS declarations capped; formatting track folded into M-071; tenant theme not started (no contract)");
note("M-119", "S4 matrix after S2 wave 2 (HARNESS): remaining FAIL cells are studio/new, platform/costs NaN-pattern fixed? re-check in the final run");

// ---- integration round 6 (agent/c5-web after 35e7520 + S4 wave 4): S1 milestone 2 merged; REAL-STACK findings (S4 baseline, private builds, backend 47080, HEAD bd23f89)
status("M-011", "FIXED (HARNESS, merged 35e7520): top bar 141 -> <=100px, 'Thêm thao tác' menu, 901-1023px row bug fixed");
status("M-104", "FIXED (HARNESS, merged 35e7520)");
status("M-016", "FIXED for Studio (HARNESS, merged 35e7520): shared toast adopted; Admin banners not toast-migrated (not needed)");
status("M-075", "STUDIO + ADMIN SCREENS FIXED in the state matrix (error/permission cells PASS, HARNESS); Admin errorText adoption (S2 milestone 2) pending");
status("M-017", "STUDIO PART FIXED (HARNESS, merged 35e7520): 12 native confirm + 1 prompt -> 0; Admin part merged earlier");
status("M-019", "FIXED (HARNESS, merged 35e7520): rollback asks, names both versions, danger, focus on 'Hủy'; discard/secret/restore confirmations");
status("M-041", "FIXED (HARNESS, merged 35e7520)");
status("M-020", "STUDIO CALL SITES FIXED (HARNESS, merged 35e7520); Admin part merged earlier");
status("M-118", "FIXED (HARNESS, merged 35e7520); the harness overflow did not reproduce on real data (long name was in the workspace name)");
status("M-119", "FIXED for studio/new (HARNESS, merged 35e7520)"); status("M-122", "FIXED for studio/new (HARNESS, merged 35e7520)");
status("M-125", "FIXED (HARNESS, merged 35e7520): targets 9 -> 0; remaining 'covered' = detector reads clipped-by-own-scroll as covered");
status("M-025", "ADMIN + STUDIO SHELLS FIXED (HARNESS, merged eb38a2d + 35e7520)");
status("M-030", "FIXED (HARNESS, merged 35e7520): RadioGroup for the audience"); status("M-031", "STUDIO PART FIXED (HARNESS, merged 35e7520): ReasonButton / GuardedButton; Admin part pending (S2)"); status("M-028", "STUDIO PART FIXED (HARNESS, merged 35e7520); Admin part pending (S2)");
rows.push(["M-126","P2","C5","Studio","header · 600px · REAL STACK","Studio header at 600px: the search field 'Tìm ứng dụng' is squeezed to 22x36px and covered by the workspace picker on EVERY Studio screen (8 routes) when the workspace name is long ('Kinh doanh và Chăm sóc khách hàng')",["S4R-01"],"features/studio StudioApp header + header CSS (REAL STACK finding; reproduced in the harness: scripts/ui-repro.mjs --only studio-header-600)","S1 (+S3 header CSS)","none","N (reproduced in HARNESS; found on the REAL STACK)","wrap/stack the header at <=700px, give the search a min width, ellipsis the workspace name","real-stack matrix 600px: 0 covered; harness repro NO",""]);
rows.push(["M-127","P2","C5","Studio","AI view · 768px · REAL STACK","Studio AI view at 768px: the starter chip 'Rút gọn tiêu đề hero' is covered by div.composer when AI is not enabled",["S4R-02"],"ProjectWorkspace AI pane layout at 768 (reproduced: ui-repro --only studio-ai-768)","S1","none","N (harness repro; found on the REAL STACK)","reserve space / scroll the conversation above the composer","matrix 768 covered 0",""]);
rows.push(["M-128","P2","C5","Platform","AI provider dialog · 360px · REAL STACK","Platform 'Thêm nhà cung cấp' dialog at 360px: 'Nâng cao' is covered by the sticky footer 'Hủy / Lưu'; check that the dialog scrolls so the button can be reached",["S4R-03"],"Modal footer sticky + dialog body height (reproduced: ui-repro --only dialog-sticky-footer-360)","S2 (dialog) + S3 (Modal footer / scroll-padding)","none","N (harness repro; found on the REAL STACK)","scroll-padding-bottom equal to the footer height inside modalBody; verify reachability by keyboard","matrix 360 covered 0",""]);
rows.push(["M-129","P2","C5","Studio","builder rail Thành phần · 1024px · REAL STACK","Builder at 1024px with the rail 'Thành phần': the drag/add buttons of components are covered by aside.bx-right (the properties panel is a full-width bottom row y 744-900 and the real component list reaches it); data-dependent, NOT reproduced in the harness even with 24 components",["S4R-04"],"builder.css 761-1100 grid: .bx-right grid-column 1/-1 under a scrolling left panel (real registry list is long)","S1 (builder.css) + S3","none","Y (needs the real component registry list)","give the left panel its own scroll area above the bottom row or cap its height so items never sit under .bx-right","real-stack matrix 1024px: 0 covered",""]);
rows.push(["M-130","P3","C5","Studio","activity · 1920px · REAL STACK","'mở ứng dụng' link on /studio/activity is 86x21px at 1920 (<24px target)",["S4R-05"],"features/studio/screens/Activity.tsx link","S1","none","N","min-height 24px","matrix smallTargets 0",""]);
rows.push(["M-131","P3","C5","Studio","home · REAL STACK","10 text leaves under 11px on Studio home (not identified)",["S4R-06"],"unknown (matrix font-size probe)","S1/S3","none","N","identify and raise to >=12px or remove","matrix tinyText 0",""]);
rows.push(["M-132","P3","C5","Platform","costs · REAL STACK","/platform/costs shows 'Tổng đã biết $0 — đủ đơn giá' while all 3 price lines say 'chưa có đơn giá' (may be legitimate with zero usage)",["S4R-07"],"AdminApp costs page KPI hint logic (features/admin/pages)","S2","none","Y (needs cost data)","show 'chưa đủ đơn giá' when any price line is missing","admin harness costs fixtures",""]);

// ---- integration round 7 (agent/c5-web 1bd7a4d): S2 wave 2 milestone 2 (committed part, merged 9273b93), salvage d319531, REAL-STACK findings closed by C5-L (S1/S2/S3 were rate-limited)
status("M-075", "FIXED (HARNESS): Studio errorText (35e7520) + Admin adminErrorText/provisioningProblem replaced by the shared mapper (merged 9273b93); state matrix error/403 cells PASS");
status("M-029", "FIXED (HARNESS, merged 9273b93): DisclosureRow for audit rows");
status("M-031", "FIXED (HARNESS): ReasonButton in Studio (35e7520) and Admin (9273b93)");
status("M-032", "FIXED (HARNESS, merged 9273b93): Pill semantic keys");
status("M-028", "FIXED (HARNESS): Studio (35e7520) + Admin nav links with aria-current / Tabs (9273b93)");
status("M-034", "FIXED (HARNESS, merged 9273b93): FormError scrolls the server error into view above the sticky footer");
status("M-105", "FIXED (HARNESS): CSS pattern (c51c705) + the five Admin dialogs use ModalHeader (merged 9273b93)");
status("M-111", "FIXED (HARNESS, merged 9273b93): organization tree windowed above 1000 visible rows, one path map, move dialog reuses the tree memo; org-hardening 70/70");
status("M-062", "PARTIAL (merged 9273b93): glossary wording in the wave-2 Admin strings, ratchet ceilings lowered; remaining offenders tracked by the ratchets");
status("M-095", "FIXED (HARNESS, salvage d319531): nav marks 'Sắp có'; one not-ready note in the employee dialog");
status("M-096", "FIXED (HARNESS, salvage d319531): AI page says it is platform-wide");
status("M-012", "FIXED (HARNESS): shared CSS (da6c70f) + delete confirm is btn danger (35e7520)");
status("M-016", "FIXED (HARNESS, merged da6c70f + 35e7520)");
status("M-017", "FIXED (HARNESS): Admin 21 + Studio 12/1 native dialogs replaced by confirm()/prompt(); guard tests");
status("M-020", "FIXED (HARNESS): Admin (eb38a2d) + Studio (35e7520) call sites");
status("M-119", "FIXED (HARNESS): Admin screens (eb38a2d) + studio/new (35e7520)"); status("M-122", "FIXED (HARNESS): admin/identity (eb38a2d) + studio/new (35e7520)");
status("M-126", "FIXED (REAL STACK + HARNESS): the long workspace-name header squeeze was closed by S1's M-118 (merged 35e7520, after S4's baseline commit bd23f89); real-stack re-run at HEAD (Studio 600/768/1024, 102 visits): 0 covered, 0 small targets; ui-repro studio-header-600 = NO");
status("M-127", "FIXED (REAL STACK + HARNESS): AI view at 768 no longer overlaps (S1 M-011/M-104, merged 35e7520); real-stack re-run: 0 covered; ui-repro studio-ai-768 = NO");
status("M-128", "CLOSED, NOT A DEFECT (HARNESS + keyboard test): a control below the fold under a sticky footer is reachable: Tab focus scrolls it clear (adv bottom 558 <= footer top 571 at 360x640); the audit detector now re-tests after scrollIntoView (ec81e49); admin spec DLG04 guards WCAG 2.4.11");
status("M-129", "CLOSED, DETECTOR CASE (REAL STACK): the items sit at the bottom edge of the left panel's own scroll area; harness boxes show left panel bottom == properties row top (744) with no overlap; scrolled into view they are clear; real-stack re-run at 1024 with the updated detector: 0 covered");
status("M-130", "FIXED (HARNESS, 8ce992d): activity links >= 24px; studio-wave3 check; real-stack re-run: 0 small targets at 600/768/1024");
status("M-131", "FIXED (HARNESS, 8ce992d): <small> floor of 12px (was 10.8-11.7px); studio-wave3 checks on 3 screens");
status("M-132", "FIXED (HARNESS, 1bd7a4d): costs hint says which price lines are missing, never 'đủ đơn giá' with missing prices; CST01/CST02");
status("M-125", "FIXED (HARNESS, merged 35e7520) + detector corrected (ec81e49): 'covered' now re-tests after scrollIntoView");

// ---- integration round 8 (agent/c5-web 1e46c91): C5-L remediation of Studio P2s (S1/S2/S3 rate-limited); every fix has a failing-before regression check (HARNESS, NOT REAL BACKEND)
status("M-049", "FIXED (HARNESS, 01f8487): a ?prompt= address only pre-fills the composer; the prompt typed on Home is handed over in memory (promptHandover.ts); 5 checks (3 failed before)");
status("M-050", "FIXED (HARNESS, 7e0a680): clone token masked by default (Hiện/Ẩn, Sao chép), credential-free git command, a missing token says so (no 'null'); 6 checks");
status("M-045", "FIXED (HARNESS, 9500db3): beforeunload armed while a code draft exists; 'Bỏ nháp' asks first (names files, danger, 'Giữ lại'); 5 checks");
status("M-044", "FIXED (HARNESS, b3b3cd0): a page dialog that cannot save shows the mapped reason + reference code inside the dialog; 2 checks");
status("M-038", "FIXED (HARNESS, 27449eb): menu / 404 / theme editors re-sync from the saved document (keyed): a removed page leaves no stale link for 'Lưu menu'; 3 checks");
status("M-037", "FIXED (HARNESS, 5924e54): opened rail panels stay mounted via React Activity (state kept, effects paused while hidden): half-filled editors survive switching tab; 3 checks");
status("M-036", "FIXED (HARNESS, 3196633): phone-only note that the builder is for viewing and light edits");
status("M-035", "FIXED (HARNESS, 4806f9d): members drawer fits 390 and 360 with long unbreakable names (fixed table layout, wrapping name column, shrinking role select)");
status("M-043", "FIXED (HARNESS, ba62577): the Code mode button says 'Sắp có' on the tab itself; the placeholder page still offers both ways back");
status("M-021", "FIXED (HARNESS, 1e46c91): builder Dialog closes with Escape at document level; a press that starts inside and ends on the backdrop never closes it");
status("M-023", "FIXED (HARNESS): Modal (da6c70f) + Studio drawers (35e7520) + builder Dialog (1e46c91) all on the shared overlay stack (top-only Escape, trap, scroll lock restored in any order)");

// ---- integration round 9 (agent/c5-web, Wave A merged: S3 6842f65, S2 9b67da9, S1 merge): fresh isolated Wave-A agents; all browser evidence is HARNESS, NOT REAL BACKEND
status("M-106", "FIXED (HARNESS, c495040): workflow steps named by readable title ('Bước N · kind: name') in targets/branches/chips/aria/validation; approver kinds in Vietnamese; retry backoff in seconds; cron presets not done");
status("M-046", "FIXED (HARNESS, measured, 9877afa): a pure preview scroll no longer re-renders the Builder (handle track moved by transform, rects in a ref, memoised Handle); at 1004 sections host commits 25->0, worst frame 106.9->17.8 ms, dropped 25->0. Follow-up: a scrolled-out handle is clipped yet still tabbable (keyboard focus not visible) - needs a preview-script message");
status("M-047", "FIXED (HARNESS, 74e1d90): the Website drawer uses builder core/pages (uniqueSlug, checkSlug incl. reserved slugs, removeImpact, opsSetNavigation, opsSetNotFound); own slugify and dead PageBar removed. Slug copy in features/admin/adminModel.ts remains (S2 file)");
status("M-077", "FIXED (HARNESS, 74e1d90): add-page dialog and drawer show the slug that is really saved (uniqueSlug)");
status("M-042", "CLIENT HALF FIXED (HARNESS, 5d4c585): Test mode/preview banners say the preview is view-only and where to try; Theme panel says the theme is saved but not applied in preview/published site. Renderer half = handoff HF-C2-04 (C2) open");
status("M-088", "FIXED (HARNESS): Admin part (eb38a2d) + Studio RuntimeDrawer secret cleared only after a successful save, kept for retry after a failure, one save at a time (d2a126b)");
status("M-091", "FIXED (UNIT, 52cf9c5): studio-ws, studio-ai-model and ws-mode-* keys cleared on logout and dropped when a different user opens Studio");
status("M-113", "FIXED (HARNESS, d30a98f + 7d46be1): smooth scrolling only under prefers-reduced-motion:no-preference; published output changed on purpose (golden sha256 updated; undoing only that change reproduces the old golden). C2 republish note");
status("M-110", "PARTIAL (HARNESS, 9877afa): canvas drag handles memoised and found by Map; page-tree row memo/windowing and keeping the tree mounted not done");
status("M-025", "FIXED (HARNESS): Admin + Studio shells (eb38a2d, 35e7520); Platform uses the same AdminApp shell and checks SKP01-05 run on portal=platform (S2 Wave A, no change needed)");
status("M-093", "PARTIAL: (a) FIXED (HARNESS, 418fd25) a failed /auth/config shows an error with retry instead of a guessed password form (CFG01/02, platform + admin); (b) absent permissions list treated as deny BLOCKED on HF-C1-05 + real-stack check");
status("M-097", "MOSTLY FIXED (HARNESS, measured, 26b31e2): person/override searches debounced 300 ms (REQ03 7 keystrokes -> 1 request, REQ04 4 -> 1), workspace member lists loaded once, AI providers/limits on the keyed useLoad cache (REQ01 providers 2 -> 1). Remaining: api.* methods do not accept an AbortSignal (packages/api-client)");
status("M-098", "FIXED (UNIT + HARNESS, 5f9e3ae): activation/reset link uses portalOrigin('studio') when configured (token stays in the fragment); copy failure already shows a visible alert (LNK08)");
status("M-065", "FIXED (HARNESS, 16fb8ad), user decision applied: the four people screens stay separate (distinct jobs, evidence in S2-people-screens.md); consolidated shared blocks (peopleSections table, PeopleLinks, TenantSwitch, useOwnWorkspacesOf); no route change");
status("M-069", "PARTIAL (HARNESS, 8dd71bb): radius + font-size scale tokenised (206 uses, 0 of 34 snapshot screens changed); builder.css hex, globals.css, shadows and the dark palette remain");
status("M-070", "PARTIAL (HARNESS, c67f5a1): .sr-only defined (TestPanel caption no longer shows), dead 16px checkbox sizing removed, conflicting duplicates 19->17");
status("M-084", "SHELL ADOPTED (HARNESS, 22670eb): portal splashes/fallbacks and the not-found page render h1; CodeWorkspace/AI-mode h1 and table captions pending (S1/S2 files)");
status("M-067", "COMPONENT MERGED (HARNESS, 7168d4d): <Field> in @xweb/ui, adopted on the auth pages (6 fields); ~45 call sites in features/admin and features/studio pending");
status("M-060", "SHARED COPY FIXED (28d5d3f): APP_PUBLISH no longer shown, ceilings lowered (internal-constant 15->13, native dialogs 12->0); runtimeConfig DATA_API_BASE_URL wording needs a product decision");
status("M-062", "PARTIAL (28d5d3f): term-project 14->10, term-model 10->9, tone-old-style 57->30");
status("M-063", "PARTIAL (28d5d3f): auth pages use BRAND/PORTAL_LABEL; legacy-name ceiling 11->7");
status("M-061", "LIBRARY MERGED (9ea2e39), shared part verified (S3 Wave A): 25 maps + 'Khác' fallback + guard exist; adoption in S1/S2 call sites pending");

// ---- normalization pass (handoff): every previously blank status gets an explicit canonical status word
status("M-039", "BLOCKED (handoff C2 answer): domain/customDomain/deploymentTarget cannot be cleared because the server hostname regex rejects an empty value; client part waits for the C2 answer (HF-C2)");
status("M-040", "BLOCKED (handoff C2 answer): GET /ai/status?workspaceId= narrowing by access needs the C2 answer before the client sends workspaceId");
status("M-073", "BLOCKED (handoff C0): scripts/portals.sh down kills the pid in <name>.launcher without identity validation; C0 file");
status("M-090", "BLOCKED (handoff C0): HSTS opt-in / COOP / CORP / report-to headers are infra");
status("M-094", "BLOCKED (backend): 'Duyệt' visibility compares createdBy with the display name; needs a user id from the API (handoff)");
status("M-101", "BLOCKED (handoff C2 worker): renderSchemaDocument mutates module-level ctx/up/site; C5 file half can follow only with the C2 worker change");
status("M-102", "BLOCKED (handoff C0): npm run test:unit deletes .test-build (outDir); C0 script");
status("M-108", "BLOCKED (decision C0): HTML is no-store because of the per-request CSP nonce");
status("M-100", "RESEARCH_ONLY (report only): legacy root app / live helpers inventory delivered in R docs; no code change planned");
status("M-051", "OPEN (S1 Wave A deferred, budget): client validation of the access-ticket query path; server half closed in source");
status("M-078", "OPEN (S1 Wave A deferred, budget): drawer close uses router.push so Back re-opens the drawer");
status("M-080", "OPEN (S1 Wave A deferred, budget): 'Khôi phục' shown to everyone; versions with 0 rows");
status("M-081", "OPEN (S1 Wave A deferred, budget): 'Khám phá cấu trúc' + 'chưa' reads as one word");
status("M-082", "OPEN (S1 Wave A deferred, budget): lineDiff re-runs for every file on every render; needs a measurement first");
status("M-083", "OPEN (S1/S2 deferred, budget): small copy items (search box clipped, 'Chạy / thử' wraps, ...)");
status("M-089", "OPEN (S1 Wave A deferred, budget): page nonce copied into the srcdoc script and postMessage target '*'");
status("M-109", "OPEN (S1 Wave A deferred, budget): PagesPanel canStep is O(n^2); needs a micro-benchmark before and after");
status("M-112", "OPEN (S1 Wave A deferred, budget): phone page-scroll mode needs :has() with no fallback");
status("M-099", "OPEN (Wave B review, C5-L): dead exports / unused imports / 'as never' casts; only measured or provable items");
status("M-107", "OPEN (Wave B step 2, C5-L, serial after M-068): repo-wide relative-import / shim cleanup in S1/S2 files");
status("M-114", "OPEN (C5-L, approved): Firefox and Playwright WebKit installed 2026-10-09 (playwright-core 1.63.0, WebKit 26.6); nothing has been run on them yet; evidence labels CHROMIUM / FIREFOX / WEBKIT (WebKit is not Safari)");
status("M-116", "OPEN (Wave B review, C5-L): contract conformance test is silently skipped unless XWEB_CONFORMANCE_* is set (C0 runner file)");
// --- S1 Batch 2 (merge 72ad63a, agent branch tip a15246c); HARNESS evidence, NOT REAL BACKEND; light gate green (typecheck, unit 480/479/0/1, classify, studio-wave3 162, builder 111, studio-p1 16, studio-wave2 31, release 61, data-binding 34, datasources 54, hooks 20, css-snapshot 36 shots)
status("M-109", "FIXED (6d1b069): PagesPanel first/last flags via canStepAt, O(1) per row; micro-bench median of 5 at 2000 sections 78.8 ms -> 0.014 ms (1000: 22.9 -> 0.028); unit proves canStepAt equals canStep for every position");
status("M-082", "FIXED (d138931): code diff computed once per diff response (codeDiff.ts) + memoised DiffView; per render that does not change the diff 1800 lines 52.2 ms -> 0.001 ms; a new diff response still pays one LCS (~50 ms at 1800 lines, not optimised)");
status("M-078", "FIXED (db5321e): closing any drawer in ProjectWorkspace and CodeWorkspace uses history replace (opening still pushes); studio-wave3 M-078 x2. Known side effect: one Back press appears to do nothing (duplicate mode entry), accepted as safer than a push-then-back design");
status("M-080", "FIXED (e2e18b0, db5321e): archived-banner 'Khôi phục' disabled with a reason without PROJECT_DELETE (backend ProjectLifecycle.kt requires it); empty version list text; action chains as checkbox lists; a new action's open problems are not role=alert");
status("M-081", "FIXED (93177ff): NOT_READY badge read as ' (chưa sẵn sàng)' in DataWizard and Inspector; the optional roving focus for Studio cards was not done");
status("M-112", "FIXED (304b725): html.bx-page class set while the builder is mounted, builder.css repeats the two :has() rules for it; test removes every :has(.bx-root) rule at 390 px and the scroll-padding / overscroll still apply. Firefox/WebKit not executed (S4-022)");
status("M-079", "PARTIAL (db5321e test only): verified 404 shows 'Không tìm thấy' once, no retry, link back, axe 0 critical/serious; the light-page-in-a-dark-app part is an S3 design decision (.wsError uses light shell tokens, same as the project list)");
status("M-051", "PARTIAL (4b630de): client half done (path sent under the server's rule, ticket redirect only https / loopback http, no userinfo, only /_access?ticket=; site/deployment/preview/asset URLs limited to http(s) or same-origin path). BLOCKED_EXTERNAL remainder: host pinning needs the sites origin exposed to the client (C0/C2, HF-C2-03)");
status("M-089", "PARTIAL (c215475): canvas postMessage pinned to the editor origin (parentOrigin, strict pattern, '*' fallback) + escaping fuzz test (300 editor docs, 40 published sites; fails 3/3 with escaping off); golden sha256 unchanged. BLOCKED_EXTERNAL remainder: page nonce still copied into the srcdoc script, CSP sha256 is a C0 handoff");
status("M-083", "PARTIAL (a15246c): Studio header search gets its own full-width row at <=600 px (85/55 px -> 364/334 px at 390/360), 'Chạy thử' no longer wraps; nested 'Chỉ đọc' boxes not reproduced in harness test mode; S2-043 (Admin tabs/dates) is S3/Admin scope");
// --- M-114 tooling part 1 (0a7b43f)
status("M-114", "PARTIAL (0a7b43f, tooling part 1): BROWSER=chromium|firefox|webkit selector in tests/browser/lib/spec.mjs (default chromium unchanged; labels CHROMIUM/FIREFOX/WEBKIT; 'safari' refused; 9 unit tests). WEBKIT launches (Playwright WebKit 26.6, not Safari); FIREFOX_STATUS BLOCKED_TOOLING: 'Could not find profile folder' on macOS 27.0.1 with Firefox Nightly 155 and 1555, six different attempts, see audit/S4-cross-browser.md; not an accepted limitation. Cross-browser spec runs: part 2 (after product code is final)");
