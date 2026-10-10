# Data query scale - PostgreSQL connector, 1000000-row table (machine load disclosed in the test log; numbers are evidence, not an SLA)

| case | rows returned | truncated | median ms | p95 ms |
|---|---|---|---|---|
| SELECT * of 1M rows, maxRows 500 (no ORDER BY) | 500 | true | 98.3 | 188.7 |
| ORDER BY id, maxRows 200 | 200 | true | 45.6 | 71.6 |
| indexed filter kind = :k, page 100 | 100 | true | 43.5 | 89.4 |
| byte cap 64 KiB over ~230-byte rows | 274 | true | 63.4 | 124.2 |
| 20 concurrent paged queries (indexed filter) | 50 each | - | 493 total | - |
| page of 100 at offset 0 | 100 | true | 91.2 | 120.2 |
| page of 100 at offset 100 | 100 | true | 118.0 | 191.2 |
| page of 100 at offset 9900 | 100 | true | 70.4 | 115.6 |
| page of 100 at offset 100000 | 100 | true | 109.2 | 155.1 |
| page of 100 at offset 900000 | 100 | true | 1065.3 | 1590.8 |
| page of 100 at the largest allowed offset 1000000 | 0 | false | 1458.2 | 1598.8 |
| page of 10,000 (the largest page, source maxRows 10000) at offset 500,000 | 10000 | true | 857.3 | 1215.0 |
| runaway ORDER BY md5(...) over 1M rows, timeoutMs 500 | - | - | 662 | - |
