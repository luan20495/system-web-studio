# Evidence — C1 tenant-scoped provisioning, backend 2356d64 (stack c5e2e-c1b, no backend patch)

| Report | Flows | Result |
|---|---|---|
| report-…13-38-20 | SUPER01, ADMIN01, USER01, SEC01 | SUPER01 PASS · ADMIN01 PASS · SEC01 PASS · USER01 BLOCKED (C1, H-C1-04) in the run before the check was turned into the BLOCKED outcome (shows FAIL + owner C1) |
| report-…13-38-47 | AD03, USER01 | AD03 PASS (H-C1-11 closed) · USER01 BLOCKED (C1, H-C1-04) |
| report-…13-30-36 | PL01, AD01, AD02 (+ SUPER01, ADMIN01, USER01, SEC01, AD03 rate-limited) | PL01, AD01, AD02 PASS; the other five failed with `activation 429`: the backend limits activation to 30 calls / 10 min / IP — NOT a product defect and not bypassed; they were rerun after the window (the two reports above) |

No token, password or cookie is in these files (gitleaks clean; activation links are read from the DOM in memory only).
