# Data / organization database audit - plans on a seeded schema

| statement | plan nodes | exec ms | planning ms | shared hit / read | rows |
|---|---|---|---|---|---|
| credential actor lookup over audit_events (1M rows) | Limit; Sort; Bitmap Heap Scan on audit_events; BitmapOr; Bitmap Index Scan using audit_events_action_idx; Bitmap Index Scan using audit_events_action_idx | 23.23 | 14.06 | 4/122 | 1 |
| find data source (tenant, id) | Index Scan on data_sources using data_sources_id_tenant_workspace_unique | 0.10 | 0.25 | 1/2 | 1 |
| find data source (tenant, workspace, id) | Index Scan on data_sources using data_sources_id_tenant_workspace_unique | 0.09 | 0.22 | 3/0 | 1 |
| list data sources of a workspace (LIMIT 1000) | Limit; Sort; Bitmap Heap Scan on data_sources; Bitmap Index Scan using data_sources_tenant_idx | 8.12 | 0.25 | 1/101 | 20 |
| list data sources of a tenant (LIMIT 1000) | Limit; Sort; Bitmap Heap Scan on data_sources; Bitmap Index Scan using data_sources_tenant_idx | 0.75 | 0.10 | 102/0 | 100 |
| find query (tenant, source, id) | Index Scan on data_queries using data_queries_pkey | 0.16 | 4.02 | 0/4 | 1 |
| list queries of a source | Limit; Index Scan on data_queries using data_queries_pkey | 0.40 | 0.12 | 4/9 | 10 |
| find mutation (tenant, source, id) | Index Scan on data_mutations using data_mutations_pkey | 0.55 | 1.34 | 0/4 | 1 |
| runtime bindings of a project and mode (ownership joins) | Nested Loop; Nested Loop; Index Scan on data_source_bindings using data_source_bindings_pkey; Index Only Scan on data_sources using data_sources_id_tenant_workspace_unique; Index Scan on projects using projects_workspace_id_id_unique | 0.65 | 1.27 | 5/4 | 1 |
| delete guard - bindings of a source | Aggregate; Index Only Scan on data_source_bindings using data_source_bindings_source_idx | 0.09 | 0.09 | 3/0 | 1 |
| delete guard - unfinished idempotency of a source | Aggregate; Index Scan on data_idempotency using data_idempotency_pkey | 6.91 | 0.84 | 1/27 | 1 |
| latest schema snapshot of a source | Limit; Index Scan on source_schemas using source_schemas_version_unique | 0.11 | 0.47 | 0/3 | 1 |
| idempotency key lookup (PK) | Index Scan on data_idempotency using data_idempotency_pkey | 0.06 | 0.17 | 4/0 | 1 |
| purge candidates (LIMIT 1000) | Limit; Bitmap Heap Scan on data_idempotency; Bitmap Index Scan using data_idempotency_expiry_idx | 2.46 | 0.10 | 0/34 | 1000 |
| purge DELETE (rolled back) | ModifyTable on data_idempotency; Nested Loop; Aggregate; Subquery Scan; Limit; Bitmap Heap Scan on data_idempotency; Bitmap Index Scan using data_idempotency_expiry_idx; Tid Scan on data_idempotency | 7.56 | 1.04 | 2034/0 | 0 |
| delete a source's idempotency rows (rolled back) | ModifyTable on data_idempotency; Index Scan on data_idempotency using data_idempotency_pkey | 2.95 | 0.09 | 53/0 | 0 |


_foreign keys served only by a PARTIAL index_ (a hard delete of the parent would scan the child; the application never hard-deletes them): employee_positions.employee_positions_grade_fk, employee_positions.employee_positions_membership_fk, employee_positions.employee_positions_position_fk, employee_positions.employee_positions_tenant_id_fkey


_audit_events lookup_: plan `Limit; Sort; Bitmap Heap Scan on audit_events; BitmapOr; Bitmap Index Scan using audit_events_action_idx; Bitmap Index Scan using audit_events_action_idx` in 23.2 ms (finding F-3 if a sequential scan).


_structure_: 13 tables, tenant_id NOT NULL + FK to tenants on all; 0 cross-table FKs without tenant_id.

