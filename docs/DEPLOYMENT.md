# Deployment notes (single node / pilot)

Nothing here has been run in a production environment; it describes what the code expects.

## Services
PostgreSQL 17 (persistent volume, backups), Redis 8 (AOF on, `noeviction`; it stores sessions and rate limits), RabbitMQ 4 (durable queues `studio.publish`, `studio.publish.dlq`), an S3-compatible store (MinIO locally; any S3 endpoint works through `app.storage.*`), the API JAR, and the Next.js server (needs a Node runtime for the `/api` rewrite, or serve `/api` from the same reverse proxy and use any static host for the UI).

## Required environment
`SPRING_PROFILES_ACTIVE` must **not** be `local` (it enables the seed users and Swagger). Set `DATABASE_URL/USER/PASSWORD`, `REDIS_HOST/PORT` (+ password via Spring properties if secured), `RABBITMQ_HOST/PORT/USER/PASSWORD`, `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT` (the address browsers use for presigned URLs), `MINIO_ROOT_USER/PASSWORD` (use a scoped access key in real S3), `MINIO_BUCKET`, `CORS_ALLOWED_ORIGINS`, `SERVER_ADDRESS`. Keep `COOKIE_SECURE` unset (= true) and terminate TLS in front. Run an API replica count of 1 until the sweeper is reviewed for multi-instance use (it is idempotent, so extra replicas are safe but redundant).

## Health
`/actuator/health/liveness` and `/actuator/health/readiness` (db, redis, rabbit, minio) — readiness returns 503 while any dependency is down.

## Migrations
Flyway runs at startup (V1–V5); Hibernate runs in `validate` mode, so a schema drift fails fast at boot. Migrations are forward-only.

## GitHub Pages
Static demo only: `NEXT_PUBLIC_API_MODE=mock STUDIO_BASE_PATH=/system-web-studio npm run build`, publish `out/`. There is no CI in this repository by decision.
