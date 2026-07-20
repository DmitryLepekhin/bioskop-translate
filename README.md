# Bioskop Translation

Reusable Java 17 subtitle translation service for embedding in Bioskop and Fluenta. The
`bioskop-translation-core` module contains the public API and worker, while
`bioskop-translation-spring` provides Spring Boot auto-configuration. The optional
`bioskop-translation-web` module is a thin HTTP wrapper around the same core API.

## Database migrations

Translation migrations are packaged under `classpath:db/translation-migration` and use the
`V9000+` range. For a standalone application configure:

```properties
spring.flyway.locations=classpath:db/translation-migration
```

For a host application such as Fluenta configure both the host and translation locations:

```properties
spring.flyway.locations=classpath:db/migration,classpath:db/translation-migration
```

`V9001__translation_job_lease.sql` adds nullable `lease_token` and `lease_expires_at` columns.
Before deploying this version, drain application instances running a pre-lease version of the
worker. An old worker cannot renew a lease or use its token for fenced completion. A legacy
`IN_PROGRESS` row without lease data is treated as abandoned and may be reclaimed.

## Configuration

All settings use the `bioskop.translation` prefix.

| Property | Default | Description |
| --- | --- | --- |
| `storage.type` | `LOCAL` | `LOCAL` or `S3` storage. |
| `storage.local-root` | `build/bioskop-translation-storage` | Root for local storage. |
| `storage.s3-bucket` | — | S3/Spaces bucket. |
| `storage.s3-region` | `us-east-1` | S3 region. |
| `storage.s3-profile` | — | Optional AWS profile. |
| `storage.s3-endpoint` | — | Optional S3-compatible endpoint. |
| `openai.api-key` | — | Enables the default OpenAI client. Supply as a secret. |
| `openai.model` | `gpt-4.1-mini` | Responses API model. |
| `openai.endpoint` | `https://api.openai.com/v1/responses` | Override for deterministic tests or compatible gateways. |
| `openai.connect-timeout` | `PT10S` | HTTP connection timeout. |
| `openai.request-timeout` | `PT60S` | Timeout for each provider request. |
| `openai.max-attempts` | `3` | Maximum provider calls in one job attempt. |
| `openai.initial-backoff` | `PT0.25S` | Initial retry delay. |
| `openai.max-backoff` | `PT5S` | Maximum exponential/retry-after delay. |
| `openai.jitter-factor` | `0.2` | Backoff jitter from `0` to `1`. |
| `quick.immediate-max-chars` | `1000` | Compatibility limit for quick requests. |
| `quick.immediate-timeout` | `PT8S` | Compatibility timeout for quick requests. |
| `worker.enabled` | `false` | Enables the embedded scheduled worker. |
| `worker.poll-delay` | `PT5S` | Delay after a poll completes before the next poll. |
| `worker.lease-duration` | request timeout + 30 seconds (`PT90S` by default) | Ownership lease duration; must exceed the request timeout. |
| `worker.heartbeat-interval` | `PT20S` | Lease renewal interval; must be shorter than the lease. |
| `max-attempts` | `5` | Maximum claimed executions of one job. |

The provider retries connection/timeout failures, `429`, and HTTP `500`, `502`, `503`, and
`504`. Backoff is bounded exponential with jitter and respects `Retry-After` up to the configured
maximum backoff. Other HTTP errors and response parsing/validation errors are not retried.
Provider attempts are nested inside job attempts: with defaults, a job can make at most three
provider calls per claimed execution and five claimed executions, or 15 calls in total.

## Embedded worker concurrency

Enabling `bioskop.translation.worker.enabled=true` creates one scheduler in each Spring
application context. The scheduler uses a synchronous fixed-delay method and executes at most
one translation job—and therefore at most one provider call—at a time in that context. The next
poll is scheduled relative to completion of the preceding poll; correctness does not depend on
Spring Boot's default scheduler pool size.

Approximate fleet provider concurrency is the number of worker-enabled application instances.
PostgreSQL `FOR UPDATE SKIP LOCKED` distributes different jobs across instances. A unique lease
token fences renewal and completion, periodic heartbeats keep long-running jobs live, and only
an expired lease may be reclaimed.

Adding `@Async`, fixed-rate scheduling with a multithreaded scheduler, or multiple worker
threads changes this guarantee. A JVM-local Resilience4j bulkhead limits only that JVM; it is
not a fleet-wide concurrency limit.

OpenAI account/project budgets, provider rate-limit configuration, deployment scaling,
dashboards, alert thresholds, and any global fleet concurrency limit are owned by the embedding
application and its operators.

## Metrics

When a Micrometer `MeterRegistry` bean is present, the Spring module emits optional
low-cardinality metrics:

- `bioskop.translation.jobs.claimed`
- `bioskop.translation.jobs.completed`
- `bioskop.translation.jobs.failed`
- `bioskop.translation.jobs.retried`
- `bioskop.translation.jobs.reclaimed`
- `bioskop.translation.provider.calls` tagged by outcome
- `bioskop.translation.provider.duration` tagged by outcome

No monitoring backend or Micrometer registry is required. Exact pending/in-progress/failed
gauges are intentionally omitted to avoid querying the shared database during metric
collection.

## Build and verification

```bash
mvn test
mvn -pl bioskop-translation-spring -am clean install
```

Testcontainers-based concurrency tests require a running Docker-compatible environment.
