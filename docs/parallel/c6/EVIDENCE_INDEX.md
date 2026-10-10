# EVIDENCE_INDEX — where every C6 screenshot and raw artifact lives

**Policy.** PNG screenshots, `.bin` and `.tgz` files are **not** in git. They are kept in external archives (one per logical batch, SHA-256 below). Git carries only safe text evidence (logs, JSON, TSV, JUnit XML, reports) under `evidence/`, plus this index and two manifests. Nothing was deleted: the working-tree files are unchanged and each archive was verified (`tar -tzf`, `shasum -c`).

* Archive directory (outside the repo, same Mac): `/Users/hoangluan/code/xweb-c6-evidence-archives/` — `SHA256SUMS` sits next to the archives. Copy it to durable storage (shared drive / object storage) before relying on it; this file only records identity and hashes.
* `evidence-archives.tsv` — archive, batch, tested SHA, date, bytes, SHA-256, file/PNG counts, content.
* `evidence-external-files.tsv` — path, bytes and SHA-256 of **every** PNG/TGZ inside the archives (5304 files): a screenshot cited in a report can be matched to its archive and verified byte for byte.

## Archives

| Archive | Batch | Tested SHA | Date | MB | SHA-256 | Files (PNG) | Content |
|---|---|---|---|---|---|---|---|
| `c6-evidence-batch1-f894cc6-baseline.tar.gz` | 1-7 | f894cc6 (+ root logs, no SHA recorded) | 2026-10-06 | 1.3 | `b3f0721b2adfd11cfc00064eeb78015e9862e97d9e1a6f498c83fedb4aaaf082` | 114 (0) | first Mac/VM runs: mac, mac-run1-cached, mac-gate2-nocache-kotlin-oom, linux-vm-run, root logs |
| `c6-evidence-batch2-8e91172-gate-nostack-contract.tar.gz` | 1-7 | 8e91172ffc31db2c8b2eaf2eb75ba07cb8701bec | 2026-10-06/07 | 1.1 | `10db6d9443556f481b0f5c5a4099ce87b5b2c193d5b142ca28038ff3bb73e1c7` | 49 (0) | backend gate, nostack run, contract checks + DB probes + overlay tests |
| `c6-evidence-rc-62ce9697cd56.tar.gz` | 9 | 62ce9697cd56e8dc86b3260923f5addbcf555743 | 2026-10-08 | 3.4 | `5d516b832ee15d8331401fb35fc8d468c647cbc3ce891b1f180bf4e8487aad96` | 333 (24) | RC wide regression: JSON/TSV results, junit xml, e2e-real runs, shots, logs |
| `c6-evidence-user-guide-20261007.tar.gz` | 8 | public build, SHA not provable (claimed integration 1a9995c) | 2026-10-07 | 7.6 | `f6b24a83faca751232dcd27f451103f73fc7ce6c84745bcd9e1eff1ddae6dcaa` | 46 (28) | user-guide QA run, explore/probe screenshots and JSON |
| `c6-evidence-ui-ux-5cc230e491a6.tar.gz` | 10 | 5cc230e491a6d8b0135cbd7eb132793368a45073 | 2026-10-08 | 118.1 | `903565b605ce17aa9493f7f2f50c83672dff9c0a730c0fee60618a25aedae54a` | 1495 (1479) | UI/UX regression: 866 case screenshots, cases.json, axe, verify, sheets |
| `c6-evidence-ui-ux-40ee45bc16fe.tar.gz` | 11 | 40ee45bc16fe4dda525c90e0d491d11284a23d8b | 2026-10-08 | 80.7 | `b9779985b3446b37187b5872becba0959becebefd5a39db89d7c52e51e3c4540` | 944 (887) | targeted retest: 874 case screenshots, retest, process-safety, s4-regression |
| `c6-evidence-ui-ux-baseline-rc-62ce9697cd56-partial.tar.gz` | 10 | RC frontend 62ce9697cd56 (visual baseline, partial) | 2026-10-08 | 43.2 | `85d9b9e169d62a9ce52b5be29322aacebbf33dae05a57c33a36464a437576204` | 610 (608) | baseline screenshots for visual diff |
| `c6-evidence-ui-ux-pilot-pass1-no-transforms.tar.gz` | 10 | RC frontend 62ce9697cd56 (pilot, fixture without transforms) | 2026-10-08 | 64.1 | `cc7d765944df3909c3cd9d77d11a270ea74af2a1033ece59a42bd3dc02f17330` | 784 (777) | pilot pass that first exposed UX-001 |
| `c6-evidence-final-rc-ui-chromium.tar.gz` | 12 | bc5c47f292d00846c106669b09679a6fc36daef6 (served build on c0rc; stack 953d17e) | 2026-10-10 | 106.0 | `03ed0b13616c63f616844e4003da6813b1b1e8fcc85fd3d367f0d321a1e6d4ca` | 1112 (1061) | FINAL RC Chromium UI audit (9 widths, 1089 cases), retests, strict clipping detector (clip, clip2) |
| `c6-evidence-final-rc-ui-webkit.tar.gz` | 12 | bc5c47f292d00846c106669b09679a6fc36daef6 (served build on c0rc; stack 953d17e) | 2026-10-10 | 54.8 | `51753c0151426bd2a663b66771ce5da7fe92bd1a35903a26bbc3d2d87949e1a6` | 389 (375) | FINAL RC WebKit UI audit (3 widths, 392 cases) + navigation retest |
| `c6-evidence-final-rc-other.tar.gz` | 12 | bc5c47f292d00846c106669b09679a6fc36daef6 (c0rc) + own stack c6fin (fad4a7b4356f, same product) | 2026-10-10 | 9.7 | `7c7c505b3aab81a19244e91afc15effbe88e168a3a0bd82684b0bac1c680877d` | 134 (60) | FINAL RC journeys 01-08, PD02, published site, brand shots (local+public), performance, C5 flow runs, coordinator-notes |

## Restore / verify
```bash
cd /Users/hoangluan/code/xweb-c6-evidence-archives && shasum -a 256 -c SHA256SUMS
tar -xzf c6-evidence-ui-ux-40ee45bc16fe.tar.gz -C docs/parallel/c6/evidence   # from the worktree root: puts the PNGs back next to the committed text evidence
```

## Excluded on purpose (not in git and **not** archived)
| What | Size | Why |
|---|---|---|
| `evidence/rc-62ce9697cd56/backend-junit-xml/binary/*.bin` (Gradle `output-events.bin`, `results-generic.bin`) | 5.4 MB | generated, reproducible by re-running the backend tests; the JUnit XML next to it is committed |
| `__pycache__`, `*.pyc`, `_to_delete/` | 25 KB | generated caches (the harness sets `PYTHONDONTWRITEBYTECODE`); ignored by `.gitignore` |
| caches, `node_modules`, `.next`, `.DS_Store` | 0 | never inside `docs/parallel/c6` |
| secrets / tokens | 0 | none found: scan of every committed text file for private keys, cloud/GitHub/Slack tokens, JWTs and `password|secret|api_key|token = "…"` found only two benign hits (a `RENDER_TOKEN="$RENDER_TOKEN"` shell variable reference in a process-exit message, and the fake `apiKey: "sk-test-123"` posted to the test API); credentials used by the harnesses are read from files outside the repo and never echoed |

## What is committed under `evidence/` (text only)
Run logs (`RESULTS.txt`, `*.log`), `*.json`/`*.tsv`/`*.psv` result tables, JUnit XML, `*.txt` probes, bug lists, `cases.json` per UI run. Roughly 11 MB. The ignore rules are in `evidence/.gitignore`.

## Batch → report → evidence
| Master batch | Report | Evidence |
|---|---|---|
| 1–7 | `QA_MASTER.md`, `QA_STATUS.md`, `BUGS.md`, `MAC_QA_HANDOFF.md` | `evidence/mac*`, `evidence/linux-vm-run-20261006`, root logs |
| 8 | `USER_GUIDE_QA.md`, `USER_GUIDE_QA_MATRIX.md`, `USER_GUIDE_QA.tsv` | `evidence/user-guide-20261007/` |
| 9 | `RC_WIDE_REGRESSION_62ce9697cd56.md` | `evidence/rc-62ce9697cd56/` |
| 10 | `UI_UX_REGRESSION_5cc230e.md` | `evidence/ui-ux-regression/5cc230e491a6/` (+ `_baseline…`, `_pilot…`) |
| 11 | `UI_UX_RETEST_40ee45b.md` | `evidence/ui-ux-regression/40ee45bc16fe/` |
| 12 | `QA_MASTER_NORMALIZED.md`, `evidence/final-rc-bc5c47f292d0/coordinator-notes.md`, `FINAL_RC_QA_REPORT.md` | `evidence/final-rc-bc5c47f292d0/` (text) + the three `c6-evidence-final-rc-*` archives (screenshots) |
