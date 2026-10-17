# Distributed Job Scheduler

A small, interview-friendly Spring Boot job scheduler. The API stores jobs in MySQL; one scheduled worker claims due jobs and dispatches them to a handler registry. Kafka, Redis, retries, and distributed locking are intentionally deferred.

Architecture: `api` (HTTP DTOs/controllers) -> `common` (job model/repository) <- `worker` (poller and pluggable handlers).

## Run

Run `mvn test` to compile and verify the project. With Docker Desktop running, run `docker compose up --build` to start MySQL and the application. Java 25 and Maven are required. Health is at `/actuator/health`; OpenAPI UI is at `/swagger-ui.html`.

## Examples

```bash
curl -X POST http://localhost:8080/api/jobs -H 'Content-Type: application/json' -d '{"jobType":"EMAIL","payload":{"to":"student@example.com"}}'
curl http://localhost:8080/api/jobs/1
curl 'http://localhost:8080/api/jobs?status=SUCCESS&limit=20'
```

Add a new job type by creating one `JobHandler` component with a unique `type()`; the registry discovers it automatically.
