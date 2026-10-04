# ADR 0018: Per-application database isolation
Status: accepted and implemented (2026-10-04).

* Generated apps never touch the platform database: they use a separate Postgres server (`appdb`) on the internal `apps` network; the
  platform database is on a different network and unreachable from app containers (verified live: name not resolvable, no route).
* One database and one LOGIN role per app, both named `app_<12 hex of the project id>` (generated, never user input). The role owns its
  database and its `public` schema; `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`, 20 connections max.
* `CONNECT` is revoked from PUBLIC on every database (`postgres`, `template1`, `appdb`, each app database); each role is granted CONNECT on
  its own database only. Verified: an app role connecting to `appdb`, `postgres` or `template1` gets "permission denied".
* The role password (random 24 bytes, hex) is stored encrypted (ADR 0017) and handed to the container as `DATABASE_URL`; the Studio shows
  only the database name. The admin credential of `appdb` lives only in the API's environment.
* Lifecycle: archive/delete stops the app; the database is kept (retention/backup decisions in ADR 0020). Backups of `appdb` are part of
  the scheduled backup job (stage L).
* Alternative considered: one database with a schema per app — rejected: weaker isolation (shared catalog, easy cross-schema grants).
