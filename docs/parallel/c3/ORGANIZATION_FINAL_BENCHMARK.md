<!-- Re-run of OrgBenchmarkTests on integration/v2 @ fad4a7b (production repositories, shipped V32). Load average ~21-30 on a shared Mac: compare the shape, not one number. -->

# Dynamic organization - FINAL-SCHEMA benchmark (production repositories)

**FINAL-SCHEMA BENCHMARK of the PRODUCTION repositories** (schema = the shipped `V32__dynamic_organization.sql`, applied by Flyway). PostgreSQL 17.6 in Testcontainers on a developer Mac, default configuration. "first" = first execution after seeding + ANALYZE (plan-cold, NOT disk-cold: the data was just written). Numbers show the shape of the curve, they are not an SLA.

- engine: PostgreSQL 17.6 (Debian 17.6-2.pgdg13+1) on aarch64-unknown-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit; shared_buffers=128MB; work_mem=4MB
- dataset: 2000 units, 5 roots, max depth 64 levels (a depth-60 chain + a spine of 12), broadest node 301 children; subtree sizes small=6 medium=96 large=1602
- 10000 employees (tenant members), 12824 memberships, 2754 employees with several memberships (27%), 8885 employee positions, 50 positions, 10 grades; a second tenant of 500 units / 2000 employees; seed 20261008

| case | rows | EXPLAIN exec ms (first) | EXPLAIN exec ms (median of 6 more) | planning ms | wall ms (first) | wall ms (median) | wall ms (p95) | buffers hit/read | plan nodes |
|---|---|---|---|---|---|---|---|---|---|
| roots | 5 | 0.11 | 0.07 | 0.11 | 49.38 | 3.87 | 10.17 | 3/0 | Sort; Index Scan using organization_units_children_idx |
| direct children of the broad node (301) | 301 | 0.84 | 0.52 | 0.30 | 50.51 | 12.50 | 28.78 | 12/0 | Sort; Bitmap Heap Scan on organization_units; Bitmap Index Scan using organization_units_sibling_code_unique |
| direct children of a spine node | 2 | 0.39 | 0.07 | 0.11 | 1.65 | 2.31 | 5.95 | 4/0 | Index Scan using organization_units_children_idx |
| ancestors of the deepest unit (depth 64) | 64 | 3.25 | 0.49 | 1.19 | 24.47 | 4.02 | 8.94 | 192/0 | Sort; Recursive Union; Index Scan using organization_units_id_tenant_unique; Nested Loop; WorkTable Scan; CTE Scan |
| depthOf the deepest unit (C1 seam) | 64 | 0.39 | 0.54 | 0.39 | 2.26 | 2.03 | 3.17 | 192/0 | Aggregate; Recursive Union; Index Scan using organization_units_id_tenant_unique; Nested Loop; WorkTable Scan; CTE Scan |
| small subtree (6) - ordered descendants | 6 | 7.72 | 0.65 | 0.42 | 5.48 | 3.53 | 6.39 | 20/0 | Limit; Recursive Union; Index Scan using organization_units_id_tenant_unique; Nested Loop; WorkTable Scan; Index Scan using organization_units_children_idx; WindowAgg; Sort; CTE Scan; Merge Join |
| medium subtree (96) - ordered descendants | 96 | 3.75 | 2.19 | 0.72 | 6.39 | 5.03 | 10.88 | 287/0 | Limit; Recursive Union; Index Scan using organization_units_id_tenant_unique; Nested Loop; WorkTable Scan; Index Scan using organization_units_children_idx; WindowAgg; Sort; CTE Scan; Merge Join |
| large subtree (1602) - ordered descendants | 1602 | 68.54 | 16.95 | 0.33 | 33.48 | 18.24 | 25.23 | 4525/0 | Limit; Recursive Union; Index Scan using organization_units_id_tenant_unique; Nested Loop; WorkTable Scan; Index Scan using organization_units_children_idx; WindowAgg; Sort; CTE Scan; Merge Join |
| large subtree (1602) - C1 seam subtree() | 1602 | 8.73 | 3.80 | 0.17 | 31.48 | 8.91 | 20.82 | 4525/0 | Sort; Recursive Union; Index Scan using organization_units_id_tenant_unique; Nested Loop; WorkTable Scan; Index Scan using organization_units_children_idx; CTE Scan |
| full tree (2,000 units), bounded | 2000 | 108.75 | 71.09 | 0.43 | 49.92 | 54.24 | 78.37 | 3203/0 | Limit; Recursive Union; Index Scan using organization_units_children_idx; Hash Join; Seq Scan on organization_units; Hash; WorkTable Scan; WindowAgg; Sort; CTE Scan; Merge Join |
| direct + subtree DISTINCT counts, small unit | 1 | 87.91 | 6.93 | 1.60 | 115.44 | 8.33 | 27.59 | 359/0 | Merge Join; Index Scan using organization_units_id_tenant_unique; Recursive Union; CTE Scan; Nested Loop; WorkTable Scan; Index Scan using organization_units_children_idx; Hash Join; Seq Scan on employee_organization_units; Hash; Index Scan using tenant_members_user_idx; Sort; Aggregate |
| direct + subtree DISTINCT counts, medium unit | 1 | 31.69 | 12.56 | 1.37 | 10.38 | 12.28 | 22.73 | 2858/0 | Merge Join; Index Scan using organization_units_id_tenant_unique; Recursive Union; CTE Scan; Nested Loop; WorkTable Scan; Index Scan using organization_units_children_idx; Hash Join; Seq Scan on employee_organization_units; Hash; Index Scan using tenant_members_user_idx; Sort; Aggregate |
| direct + subtree DISTINCT counts, large unit | 1 | 86.09 | 31.66 | 0.50 | 21.91 | 48.76 | 84.82 | 29776/0 | Merge Join; Index Scan using organization_units_id_tenant_unique; Recursive Union; CTE Scan; Nested Loop; WorkTable Scan; Index Scan using organization_units_children_idx; Hash Join; Seq Scan on employee_organization_units; Hash; Index Scan using tenant_members_user_idx; Sort; Aggregate |
| counts for the 301 children of the broad node (one tree level, lazy UI) | 301 | 60.59 | 38.76 | 0.94 | 29.31 | 67.67 | 205.40 | 443/0 | Hash Join; Bitmap Heap Scan on organization_units; Bitmap Index Scan using organization_units_pkey; Recursive Union; CTE Scan; Merge Join; Sort; WorkTable Scan; Seq Scan on organization_units; Seq Scan on employee_organization_units; Hash; Aggregate; Seq Scan on tenant_members; Subquery Scan |
| counts for EVERY unit (whole tree, bulk) | 2000 | 4122.43 | 1160.83 | 1.00 | 344.40 | 310.92 | 569.56 | 3615/0 | Hash Join; Seq Scan on organization_units; Recursive Union; CTE Scan; Merge Join; Sort; WorkTable Scan; Seq Scan on employee_organization_units; Hash; Aggregate; Seq Scan on tenant_members; Materialize; Subquery Scan |
| activeCountByUnit (C1 seam: the archive blocker) | 19 | 0.90 | 0.09 | 0.15 | 64.28 | 2.56 | 7.18 | 21/0 | Aggregate; Index Only Scan using employee_organization_units_unit_idx |
| employee directory, first page (100) [page query EXPLAIN; wall = page + total] | 100 | 148.39 | 14.66 | 0.28 | 121.15 | 14.39 | 19.69 | 310/0 | Limit; Sort; Hash Join; Seq Scan on users; Hash; Seq Scan on tenant_members |
| employee directory, offset 1,000 [page query EXPLAIN; wall = page + total] | 100 | 28.82 | 22.65 | 0.31 | 24.54 | 19.83 | 29.24 | 310/0 | Limit; Sort; Hash Join; Seq Scan on users; Hash; Seq Scan on tenant_members |
| employee directory, offset 5,000 [page query EXPLAIN; wall = page + total] | 100 | 19.30 | 20.31 | 0.21 | 38.68 | 25.59 | 37.67 | 310/0 | Limit; Sort; Hash Join; Seq Scan on users; Hash; Seq Scan on tenant_members |
| employee directory, offset 9,900 (deepest full page) [page query EXPLAIN; wall = page + total] | 100 | 24.25 | 24.65 | 0.34 | 41.40 | 37.71 | 75.62 | 310/0 | Limit; Sort; Hash Join; Seq Scan on users; Hash; Seq Scan on tenant_members |
| employee search, name contains 'nguyen anh' [page query EXPLAIN; wall = page + total] | 30 | 22.15 | 24.37 | 0.36 | 64.30 | 44.14 | 84.91 | 299/0 | Limit; Sort; Nested Loop; Seq Scan on users; Index Scan using tenant_members_user_idx |
| employee search, e-mail (one hit) [page query EXPLAIN; wall = page + total] | 1 | 17.90 | 16.35 | 0.35 | 38.70 | 64.57 | 103.28 | 200/0 | Limit; Sort; Nested Loop; Seq Scan on users; Index Scan using tenant_members_user_idx |
| org filter, medium subtree (96 units) [page query EXPLAIN; wall = page + total] | 100 | 71.32 | 10.79 | 0.83 | 41.52 | 21.12 | 48.25 | 2523/0 | Limit; Sort; Nested Loop; Hash Join; Seq Scan on tenant_members; Hash; Index Only Scan using employee_organization_units_unit_idx; Index Scan using users_pkey |
| org filter, large subtree (1602 units) [page query EXPLAIN; wall = page + total] | 100 | 60.40 | 34.19 | 5.19 | 123.46 | 91.51 | 164.50 | 479/0 | Limit; Sort; Hash Join; Seq Scan on users; Hash; Seq Scan on tenant_members; Index Only Scan using employee_organization_units_unit_idx |
| position filter [page query EXPLAIN; wall = page + total] | 100 | 10.61 | 3.05 | 0.34 | 12.34 | 15.88 | 35.08 | 765/0 | Limit; Sort; Nested Loop; Hash Join; Seq Scan on tenant_members; Hash; Index Scan using employee_positions_position_idx; Index Scan using users_pkey |
| grade filter [page query EXPLAIN; wall = page + total] | 100 | 10.83 | 14.43 | 0.43 | 12.55 | 26.31 | 40.44 | 2176/0 | Limit; Sort; Nested Loop; Hash Join; Seq Scan on tenant_members; Hash; Bitmap Heap Scan on employee_positions; Bitmap Index Scan using employee_positions_grade_idx; Index Scan using users_pkey |
| combined: org (medium) + name 'tran' + active [page query EXPLAIN; wall = page + total] | 76 | 7.25 | 6.69 | 0.65 | 14.84 | 15.04 | 29.49 | 2523/0 | Limit; Sort; Nested Loop; Hash Join; Seq Scan on tenant_members; Hash; Index Only Scan using employee_organization_units_unit_idx; Index Scan using users_pkey |
| page enrichment: memberships of the 100 ids of a page (listForUsers) | 128 | 11.96 | 0.17 | 0.27 | 55.66 | 2.81 | 6.24 | 132/0 | Aggregate; Index Only Scan using employee_organization_units_active_unique |
| page enrichment: positions of the 100 ids of a page (listForUsers) | 91 | 14.32 | 0.52 | 0.51 | 25.95 | 3.67 | 6.98 | 103/0 | Aggregate; Index Only Scan using employee_positions_user_idx |
| MOVE subtree of 151 units (transaction: structural lock + cycle check + CAS + parent update) | 1 | NaN | NaN | NaN | 178.51 | 7.12 | 14.42 | 0/0 |  |

- C0 / C7 initial targets: direct children p95 < 200 ms -> measured p95 5.95 ms (spine node) / 28.78 ms (300 children); employee search page p95 < 300 ms -> measured p95 84.91 ms (name) / 103.28 ms (e-mail), first page 19.69 ms, deepest page 75.62 ms; full 2,000-unit tree median 54.24 ms / p95 78.37; move median 7.12 ms / p95 14.42; counts per tree level median 67.67 ms vs whole tree 310.92 ms.


# Dynamic organization - FINAL-SCHEMA concurrency (production repositories)

**FINAL-SCHEMA BENCHMARK of the PRODUCTION repositories** (schema = the shipped `V32__dynamic_organization.sql`, applied by Flyway). PostgreSQL 17.6 in Testcontainers on a developer Mac, default configuration. "first" = first execution after seeding + ANALYZE (plan-cold, NOT disk-cold: the data was just written). Numbers show the shape of the curve, they are not an SLA.

| scenario | ops | threads | pool | wall ms | ops/s | latency p50 ms | p95 ms | max ms | lock wait p50 / p95 / max ms | lock timeouts | deadlocks | version conflicts | max active / max awaiting connections | outcomes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| distributed: 100 ops over 10 tenants (mixed), pool 10 | 100 | 100 | 10 | 749.94 | 133.34 | 215.68 | 528.86 | 746.14 | 0.00 / 0.00 / 0.00 | 0 | 0 | 0 | 8 / 98 | {CYCLE=1, DUPLICATE:code=1, OK=98} |
| hot tenant: 100 structural moves, pool 10, lock_timeout 5 s | 100 | 100 | 10 | 1405.99 | 71.12 | 1154.94 | 1360.68 | 1383.84 | 0.00 / 0.00 / 0.00 | 0 | 0 | 0 | 10 / 97 | {CYCLE=1, DUPLICATE:code=13, OK=86} |
| hot tenant: 100 structural moves, pool 32, lock_timeout 5 s | 100 | 100 | 32 | 598.35 | 167.13 | 352.85 | 563.82 | 595.66 | 0.00 / 0.00 / 0.00 | 0 | 0 | 0 | 11 / 99 | {CYCLE=2, DUPLICATE:code=10, OK=88} |
| hot tenant: 100 structural moves, pool 32, lock_timeout 20 ms | 100 | 100 | 32 | 440.24 | 227.15 | 259.55 | 410.77 | 440.09 | 0.00 / 0.00 / 0.00 | 26 | 0 | 0 | 9 / 99 | {DUPLICATE:code=10, LOCK_TIMEOUT=26, OK=64} |
| hot tenant: 50 A<->B pairs = 100 conflicting moves, pool 32 | 100 | 100 | 32 | 695.48 | 143.79 | 423.54 | 656.17 | 678.55 | 0.00 / 0.00 / 0.00 | 0 | 0 | 0 | 14 / 97 | {CYCLE=50, OK=50} |

Legend: lock wait is not measured per operation here (the move transaction includes it: see latency); max active / max awaiting = Hikari connections in use / threads waiting for a connection (sampled every 2 ms): with pool 10 and 100 simultaneous threads the DB pool, not the lock, is the first queue.
Graph integrity after every scenario: 11 of 11 tenants acyclic, every unit reachable from a root, no cross-tenant parent.
