# Evidence — C5 synced onto integration/v2 @ ae0432f (real backend, unpatched; stack c5e2e-ae, API :47080, Studio :3086, Platform :3001, Admin :3002)

| Report | Flows | Result |
|---|---|---|
| report-…15-38-36 | SUPER01, ADMIN01, USER01, SEC01 (SEC01–03 + 401 + CSRF + body injection) | SUPER01 PASS · ADMIN01 PASS (0 browser requests to the legacy workspace route; workspace body = {name}; every mutating request carries X-XSRF-TOKEN) · SEC01 PASS · USER01 BLOCKED (C1, H-C1-04) |
| report-…15-39-30 | PL01, AD01, AD02, AD03 | 4/4 PASS |

No token, password or cookie in these files (activation links are read from the DOM in memory only).
