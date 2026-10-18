# Distributed Job Scheduler

A small, interview-friendly Spring Boot job scheduler. The API stores jobs in MySQL; workers claim due jobs and dispatch them to a handler registry. Kafka, Redis, and distributed locking beyond the database claim are intentionally deferred.

Architecture: `api` (HTTP DTOs/controllers) -> `common` (job model/repository) <- `worker` (poller and pluggable handlers).

## Run

Run `mvn test` to compile and verify the project. With Docker Desktop running, run `docker compose up --build` to start MySQL and the application. Java 25 and Maven are required. Health is at `/actuator/health`; OpenAPI UI is at `/swagger-ui.html`.

## Examples

```bash
curl -X POST http://localhost:8080/api/jobs -H 'Content-Type: application/json' -d '{"jobType":"EMAIL","payload":{"to":"student@example.com"}}'
curl http://localhost:8080/api/jobs/1
curl 'http://localhost:8080/api/jobs?status=SUCCESS&limit=20'
curl -X POST http://localhost:8080/api/jobs/1/retry
```

Add a new job type by creating one `JobHandler` component with a unique `type()`; the registry discovers it automatically.

## Retries and dead letters

Jobs move from `PENDING` to `RUNNING`, then to `SUCCESS` or `DEAD`. A retryable handler failure is requeued while `attempts` is below `maxAttempts`; the default is three attempts. The delay grows exponentially from `worker.retry.base-delay-seconds` and is capped by `worker.retry.max-delay-seconds`, with small random jitter to avoid synchronized retries. Unknown job types are non-retryable and go directly to `DEAD`.

The optional `maxAttempts` request field accepts values from 1 through 10. A dead job can be manually requeued with `POST /api/jobs/{id}/retry`; this resets its attempt counter to zero while preserving the last error. The endpoint returns `409` when the job is not `DEAD` and `404` when it does not exist.
## Crash recovery

Each claimed job receives a UTC lease. While a worker executes a job, a daemon heartbeat renews that lease so long-running work is not recovered prematurely. If a worker crashes and the lease expires, the scheduler requeues the job with its attempts preserved, or moves it to `DEAD` when the maximum attempts has been reached. Late results from the old worker are ignored by the ownership guards.
