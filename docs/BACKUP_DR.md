# Backup and disaster recovery

## What holds state
| Store | Contains | Loss impact | Protection |
| --- | --- | --- | --- |
| PostgreSQL | everything authoritative (projects, schemas, versions, audit, deployments) | total | `pg_dump -Fc` (verified by `scripts/backup-restore-drill.sh`); production should add WAL archiving for point-in-time recovery |
| MinIO/S3 | uploaded assets | assets unavailable, rows remain | bucket versioning + replication, or `mc mirror` to a second target |
| Redis | sessions, rate-limit counters | everyone logs in again; counters reset | AOF is on locally (sessions survived a Redis restart in testing); no backup needed |
| RabbitMQ | in-flight publish jobs | none permanent: the recovery sweeper re-queues stale deployments from PostgreSQL | durable queues |

## Verified locally
`scripts/backup-restore-drill.sh` dumps the dev database, restores it into a scratch database, compares row counts for ten core tables and checks all 5 Flyway migrations are present (last run: all tables equal, including `audit_events` 108/108). The audit trigger does not block a restore. This proves the procedure works on this data set; it is not a measured RPO/RTO.

## Not done
Automated schedules, off-host copies, encryption of backups, restore of MinIO objects, WAL/PITR, and a timed full-stack recovery rehearsal.

## Restore order
1. Postgres (`pg_restore`), 2. object store, 3. start the API (Flyway validates), 4. Redis/RabbitMQ start empty, 5. the sweeper re-publishes unfinished deployments.
