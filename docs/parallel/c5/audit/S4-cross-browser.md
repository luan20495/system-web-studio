# C5 — cross-browser tooling (M-114), part 1

Date 2026-10-09. Evidence class: TOOLING (no spec was run on Firefox/WebKit; the matrix is part 2, after product code is final).

## Browser selector — `tests/browser/lib/spec.mjs`
- `BROWSER=chromium|firefox|webkit` (default `chromium`; empty = chromium). Evidence labels `CHROMIUM` / `FIREFOX` / `WEBKIT` (`browserLabel()`).
- `launch()` keeps the Chrome behaviour byte for byte for chromium (`executablePath: chromePath()`, `$CHROME` honoured). firefox / webkit use the Playwright-managed build (no `executablePath`, `$CHROME` ignored). A non-chromium launch prints `BROWSER=<LABEL>` once.
- Unknown value (`safari`, `chrome`, `edge`) stops with exit 2; there is no "safari": Playwright WebKit is **not** Safari and is never reported as Safari.
- Unit tests: `tests/browser/lib/spec.test.mjs` (default, case/blank handling, labels, exit 2 for unknown values). 9/9 pass (`node --test`).
- Not covered by the selector: `tests/browser/page-runtime.spec.mjs` (C2-owned, own `chromium.launch`) and `scripts/*` that call `chromium.launch` directly. Chromium-only constructs inside specs (CDP sessions, `--enable-precise-memory-info`, `executablePath`) will fail on the other engines and have to be found by the part-2 run.

## Launch check through the selector (2026-10-09, macOS 27.0.1 arm64, playwright-core 1.63.0)
| BROWSER | result |
|---|---|
| (unset) / chromium | OK, Chrome 155.0.8059.40 |
| webkit | OK, WebKit 26.6 (Playwright WebKit, not Safari), `setContent` OK |
| firefox | FAIL: `Could not find profile folder` |
| safari | exit 2, "not supported" |

## FIREFOX_STATUS: BLOCKED_TOOLING
Command: `node tests/…` → `firefox.launch()` ⇒ `…/ms-playwright/firefox-1543/firefox/Nightly.app/Contents/MacOS/firefox -no-remote -headless -profile <tmp> -juggler-pipe -silent`, exit code 1 after ~0.7 s, stderr `*** You are running in headless mode.` / `Could not find profile folder.`
Environment: macOS 27.0.1 (26A434) arm64, Firefox Nightly 155.0 (Playwright build 1543), playwright-core 1.63.0, Node from the repo.
Attempts, each different (none changed the result):
1. `DEBUG=pw:browser` launch: the temp profile directory exists and is passed correctly, the process exits by itself.
2. Direct run of the binary with a pre-created profile under the scratchpad, under `$HOME`, and under `/tmp`, with `-profile` and `--profile`: same message.
3. Direct run with **no** `-profile` (own `$HOME`): same message, so it is not the profile path. `-CreateProfile` additionally prints `sandbox_extension_issue_file_to_process failed … plugin-container.app: 1 (Operation not permitted)` and LaunchServices errors from the GPU helper.
4. Run outside the tool sandbox: same.
5. `MOZ_DISABLE_{CONTENT,GPU,SOCKET_PROCESS,RDD}_SANDBOX=1`: same.
6. A newer Playwright (playwright-core 1.64.0, Firefox build 1555) installed into the scratchpad only (repo and the `~/Library/Caches/ms-playwright` cache untouched): same failure.
Conclusion: Firefox Nightly cannot resolve its profile directory on this machine's macOS 27.0.1, independent of path, Playwright version and sandbox flags; looks like an OS/Firefox-build incompatibility, not a repo problem. `firefox --version` works (155.0). Not fixable inside the repo. Remaining options, all needing the user: another host or CI image for the FIREFOX run, a macOS where Firefox starts, or the user accepting that Firefox coverage is omitted. **Not recorded as ACCEPTED_LIMITATION** (user decision).
