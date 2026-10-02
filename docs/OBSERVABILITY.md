# Observability

## Health
| Endpoint | Meaning | Public |
| --- | --- | --- |
| `/actuator/health/liveness` | the JVM is up (use for restart decisions) | yes, no details |
| `/actuator/health/readiness` | database, Redis, RabbitMQ and MinIO are reachable (use for load-balancer routing); 503 while any is down | yes, no details |

During shutdown the API stops accepting new connections, finishes in-flight requests (`SHUTDOWN_TIMEOUT`, default 30 s) and the RabbitMQ listeners stop cleanly; unacknowledged jobs are redelivered.

## Metrics (Prometheus)
Off by default. To enable: `MANAGEMENT_ENDPOINTS=health,prometheus` and `METRICS_TOKEN=<16+ random chars>`. `/actuator/prometheus` then accepts only `Authorization: Bearer <METRICS_TOKEN>`; logged-in users cannot read it, and without a token it is unreachable. Block `/actuator/` at the public reverse proxy anyway (the nginx example only forwards `/api/`, `/oauth2/`, `/login/oauth2/`) and scrape the API on the private network.
```yaml
# prometheus.yml
scrape_configs:
  - job_name: studio-api
    metrics_path: /actuator/prometheus
    authorization: { type: Bearer, credentials_file: /etc/prometheus/studio-metrics-token }
    static_configs: [{ targets: ["api:8080"] }]
```
Useful series: `http_server_requests_seconds_*` (latency/throughput per route pattern), `hikaricp_connections_*` (pool saturation), `jvm_memory_*`, `lettuce_*`/Redis command timings, `rabbitmq_*` consumers and acknowledgements. Suggested alerts: readiness down > 1 min, p95 latency, 5xx rate, Hikari pending > 0 for 1 min, dead-letter queue depth > 0, backup age, `pg_stat_archiver.failed_count`.
A Prometheus + Grafana development profile is **not** shipped (it would add memory to the default M1 stack); the snippet above is untested configuration, not a verified deployment.

## Traces (OpenTelemetry)
`micrometer-tracing-bridge-otel` and the OTLP exporter are on the classpath. Sampling is `0.0` by default (nothing is exported). To export: `TRACING_SAMPLING=0.1` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://collector:4318/v1/traces`. When a trace is active, `traceId`/`spanId` appear in the log context (JSON logs in the `prod` profile). Export to a real collector was not verified here.

## Logs
* `prod` profile: structured JSON on stdout (logstash format, includes MDC: `requestId`, and `traceId`/`spanId` with tracing). Default profile: text lines with the request id.
* One access line per request (`http_request method route status duration_ms user`): route is the **pattern** (`/api/v1/workspaces/{workspaceId}/...`), never the raw URL, query string, headers, cookies or bodies.
* Never logged: passwords, session or CSRF cookies, OIDC codes/tokens (only the exception class is logged for OIDC failures), MinIO/DB credentials, prompt text. Presigned URLs are returned to the client but not logged.
* The audit log (`audit_events`, in PostgreSQL, append-only) is separate from operational logs and is the record to use for "who did what"; it carries the same `request_id`.
* Hibernate SQL logging and Spring Security debug logging are off (`WARN`).
