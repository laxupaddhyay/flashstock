# FlashStock implementation plan

## Requirements baseline

The workspace had no FlashStock repository or root `README.md`. The complete requirements baseline is the supplied `E:\Projects\README_FlashStock.md`; it is copied verbatim to this project's root as `README.md`. The existing OAuth and rate-limiter directories are separate projects and are not modified. This document records the initial design and will be updated as milestones are implemented and verified.

## Proposed architecture

Build one Spring Boot modular monolith. Package boundaries follow product, inventory, reservation, idempotency, order, expiration, outbox/messaging, security, and observability. Controllers map validated DTOs to application services; services own use cases and transaction boundaries; repositories isolate PostgreSQL access. PostgreSQL/Flyway is authoritative for products, stock, lifecycle, idempotency, orders, and outbox. Redis is optional for cache/support only; its failure falls back to PostgreSQL or bypasses cache, never changes reservation correctness. RabbitMQ receives outbox events at least once. Consumers deduplicate by durable event ID before business effects.

### Inventory concurrency decision

Use a conditional PostgreSQL update as the primary reservation strategy:

`UPDATE inventory SET reserved_quantity = reserved_quantity + :quantity WHERE product_id = :productId AND total_quantity - reserved_quantity >= :quantity`.

The update locks/serializes the target row and checks available stock as part of the write. Require exactly one affected row; zero means unavailable. Create the reservation, idempotency result, and outbox event in the same transaction; any later failure rolls the inventory write back. Inventory adjustments use guarded SQL updates and database checks. This avoids an application read-then-write race and keeps the critical section short. `SELECT ... FOR UPDATE SKIP LOCKED` is reserved for the expiration worker, where rows must be claimed by only one worker. Reservation confirmation/cancellation/expiration first perform a conditional lifecycle transition (or locked read plus transition), then mutate stock within the same transaction; a losing transition performs no inventory operation. PostgreSQL remains the only authority for stock correctness.

### Transaction boundaries

- Product creation and initial inventory: one transaction creating product and zero/initial inventory.
- Inventory adjustment: one transaction with guarded update and invariant checks.
- Reservation create: one transaction for idempotency claim/lookup, conditional stock reserve, reservation insert, completed idempotency response, and `ReservationCreated` outbox row.
- Confirm: one transaction serializing pending reservation transition, unique order insert, reservation/inventory updates as defined by lifecycle, and `OrderConfirmed` outbox row. No external network call inside it.
- Cancel/expire: one transaction that wins a pending-to-terminal state transition and decrements reserved inventory once, with outbox row. Repeated attempts are safe no-ops or return the stable terminal result.
- Outbox relay: short DB claim/read, publish outside the DB transaction, then mark published in a separate transaction. A crash after broker confirm and before marking published causes duplicate delivery by design; consumers deduplicate.
- Expiration: short batches claim eligible rows via `FOR UPDATE SKIP LOCKED`; transition and inventory release commit atomically. A worker restart simply retries remaining pending rows.

### Consistency guarantees

Enforce `0 <= reserved_quantity <= total_quantity`, nonnegative inventory, positive reservation quantities, valid states, FK integrity, unique SKU, unique `(user_id,idempotency_key)`, and unique order per reservation in PostgreSQL. Successful reservations cannot exceed available stock because the conditional update is atomic. Idempotency fingerprint is canonical over product ID and quantity; key row, reservation, and outbox are in the same transaction, so rollback leaves no dangling claim. Reservation state changes and stock effects share one transaction, so cancellation/expiration releases at most once. Confirmation and expiration/cancellation serialize on the reservation state and only one may win. Confirmed reservation/order is unique. Message delivery is at least once, never described as exactly once.

### API contracts

All routes are under `/api/v1`; persistence entities are never exposed.

- `POST /products`: validated name, unique SKU, nonnegative decimal price, optional initial stock; returns product with totals.
- `GET /products/{id}`: product and total/reserved/available stock.
- `POST /products/{id}/inventory`: explicit initialize or signed adjustment semantics; reject resulting negative total or total below reserved.
- `POST /reservations`: authenticated principal supplies user scope; requires `Idempotency-Key`, product UUID and positive quantity; returns stable reservation DTO. Same key/fingerprint returns original DTO; same key/different fingerprint returns 409; insufficient stock returns 409.
- `GET /reservations/{id}`: owner or privileged operator only.
- `POST /reservations/{id}/confirm`: owner scoped; idempotent for already-confirmed reservation, creates one order.
- `POST /reservations/{id}/cancel`: owner scoped; a pending reservation releases once; invalid terminal transition returns 422 or stable result as documented.
- `GET /orders/{id}`: owner or privileged operator only.
- Actuator health/readiness and Prometheus metrics are separately exposed.

Use RFC 7807 problem details with stable application error codes. Validation is 400, authentication 401, authorization 403, missing resource 404, stock/idempotency conflicts 409, invalid lifecycle 422, throttling 429, unexpected errors 500. Pagination will be applied if list endpoints are introduced; the required API currently has no collection/list route.

### Authentication

Use Spring Security HTTP Basic only as an explicitly development-oriented local profile, with username/password externalized and no source-controlled default secret. Use an authenticated principal containing a UUID customer ID. Production deployment must supply its own identity provider/JWT validation configuration; no hard-coded credentials or insecure anonymous customer identity.

### Failure scenarios

- Stock exhaustion / concurrent reserve: conditional update returns zero; no reservation, idempotency completion, or outbox event commits.
- Same-key concurrency: uniqueness conflict serializes requests; second request loads original committed response; mismatched payload is 409. Transaction rollback removes the claim.
- DB error after inventory update: complete DB transaction rolls back.
- Confirm races expiry/cancel: exactly one state transition wins; only its transaction applies corresponding inventory/order effects.
- Worker crash: uncommitted row locks release; committed terminal state prevents repeat release.
- RabbitMQ down: outbox remains pending and relay retries with bounded exponential backoff; no business transaction waits on broker.
- Broker ack followed by process crash: event may redeliver; consumer inbox/processed-event unique key prevents repeated effects.
- Poison message: bounded retry then DLQ; alertable failure, no silent drop.
- Redis unavailable: bypass optional cache/support function; do not block or alter database correctness.

## Milestones and status

1. **Foundation** — Maven/Spring Boot baseline, Flyway/PostgreSQL schema, product/inventory APIs, configuration, validation, unit/integration tests.
2. **Atomic reservations** — conditional update, transactional idempotency, owner scope, deterministic PostgreSQL concurrency and rollback tests.
3. **Lifecycle/orders** — confirm/cancel, unique order, SKIP LOCKED expiration worker, lifecycle race tests.
4. **Messaging** — transactional outbox, Rabbit topology/retries/DLQ, idempotent consumers and failure tests.
5. **Operations/performance** — Redis optional cache, Actuator/Micrometer, Prometheus/Grafana, k6 scenarios and reproducible benchmark procedure/results.
6. **Delivery** — Docker Compose, multi-stage Dockerfile, `.env.example`, CI, OpenAPI, accurate README and architecture docs.

Each completed milestone will record code changes, verification results, skipped environment-dependent checks, and remaining work below. A milestone is not complete based on scaffolding alone.

## Testing strategy

- JUnit 5/Mockito unit tests for validation, inventory arithmetic, state transitions, idempotency fingerprint and response semantics, expiration eligibility, and exception mapping.
- Testcontainers against real PostgreSQL for Flyway schema/constraints, transaction rollback, repositories, and persisted service behavior. Do not use H2 for locking/conditional-update assertions.
- Required controlled 10,000-attempt test: stock 100, independent idempotency keys, bounded executor and synchronization barrier, shared PostgreSQL, then assert exactly 100 persisted reservations, reserved=100, available=0, and 9,900 stock conflicts. Record runtime, but do not present it as a benchmark unless environment details/results are captured.
- Race tests use barriers/latches and bounded polling, not arbitrary sleeps: duplicate idempotency, conflicting fingerprint, concurrent cancellation/confirmation, confirmation vs expiry, worker restart/replay.
- RabbitMQ Testcontainers: outbox retry, duplicate delivery, idempotent consumer, retry/DLQ, broker unavailable. Redis Testcontainers: optional cache behavior and failure fallback.
- Maven verify is required; container-backed tests must be reported as skipped/unavailable if Docker cannot run. k6 is runnable, but benchmark values remain explicitly unmeasured until executed on documented hardware.

## README contradictions and decisions

1. The README says the outbox may be deferred, but also requires lifecycle events, atomic event recording, and an outbox in the delivery criteria. Decision: outbox is part of the correctness path before claiming reliable messaging is complete.
2. One API request models one product and quantity, while a database discussion calls reservations plural/one-or-more products. Decision: implement one product per reservation for this version; cart-level/multi-product reservations require deadlock ordering and are future scope.
3. “Initialize or adjust inventory” is underspecified. Decision: product creation initializes stock through nonnegative `initialQuantity`; later inventory changes use a `direction` (`INCREASE`/`DECREASE`) and positive magnitude, with decreases rejected if they would go below reserved stock. This avoids ambiguous signed values and is documented in OpenAPI and README.
4. README alternates between 409 and 422 for lifecycle conflicts and does not define whether repeated cancel/confirm is idempotent. Decision: confirm of already-confirmed is success returning the existing order; only one order is inserted. Cancel/expire effects are once-only; invalid transitions have stable 422 problem codes, with terminal repeats returning the stable terminal state where safe.
5. Authentication is required for customer ownership but no identity provider is specified. Decision: provide a development-only, externally configured HTTP Basic principal and document production identity-provider integration; never use a hard-coded credential.
6. Redis is listed but optional; it must not participate in inventory correctness. Decision: optional product cache only with safe cache bypass on outage.
7. Exact-once RabbitMQ delivery is impossible to promise across DB/broker boundaries. Decision: document at-least-once relay and durable consumer deduplication.
8. The 10,000-request correctness scenario is not itself a comparable performance benchmark. Decision: keep it as correctness test; only report k6 benchmark measurements with hardware/configuration and workload details.

## Implementation log

- Initial inspection: `E:\Projects` has no Git root and no root `README.md`; the full specification was `README_FlashStock.md`. Existing `oauth-jwt-authorization-platform` and `rate-limiter-service` remain untouched. A dedicated `FlashStock` project has been created and the specification copied to `FlashStock/README.md`.
- Initial plan: architecture, transaction boundaries, concurrency approach, API contracts, failure scenarios, test strategy, and ambiguities documented before application source implementation.

## Milestone progress

| Milestone | Implementation | Verification / remaining work |
|---|---|---|
| 1. Foundation | Spring Boot 3.5.16 / Java 21 Maven app, PostgreSQL config, Flyway V1, product/inventory entities and APIs, externalized Basic development principal, DTO validation, API errors and OpenAPI metadata. | Unit validation tests pass. PostgreSQL migration/schema validation requires the Testcontainers integration run; Docker is unavailable on this machine. |
| 2. Atomic reservations | Conditional inventory SQL is default; configurable explicit row-lock comparison mode exists. Transactional scoped idempotency, request fingerprint, original reservation response, guarded inventory adjustments and outbox insertion implemented. Expiry comparisons use PostgreSQL clock time after taking the reservation lock. | Unit lifecycle tests pass. Authenticated API and PostgreSQL 10,000-request, same-key concurrency, idempotency mismatch, database constraint, and rollback tests are authored, not executed without Docker. |
| 3. Lifecycle and orders | Owner-scoped reservation commands, confirmation/cancellation rules, unique order per reservation, consumed stock accounting, scheduled batch expiration with `FOR UPDATE SKIP LOCKED`; expiration/cancel/confirm share a DB transaction with stock mutation. | Unit transition/expiration rules pass. Container tests cover concurrent cancellation/confirmation, confirmation/expiration race, repeat worker processing, and persisted stock. Not run without Docker. |
| 4. Messaging | Durable Rabbit topic exchange/queue and DLQ, bounded listener retries, transactional outbox relay, publisher confirms, retry backoff, stable event IDs, processed-event uniqueness. | Testcontainers Rabbit test covers relay, duplicate delivery and broker outage recovery. Not run without Docker. Delivery is at least once, not exactly once. |
| 5. Operations and performance | Redis product metadata cache with PostgreSQL fallback, health/readiness, Micrometer counters/histograms/backlog gauge, structured logs/correlation ID, Prometheus, Grafana, configurable k6 rate/workload and strategy runner. | k6 strategy procedure is written, but no load run or benchmark result is claimed. Redis fallback Testcontainers test is authored and needs Docker. |
| 6. Delivery | Multi-stage Dockerfile, local Compose services, placeholder `.env.example`, GitHub Actions verify workflow, OpenAPI docs, local API examples, architecture/schema/concurrency/failure/benchmark docs. | Docker Compose has not been launched in this environment because Docker is not installed. CI workflow is configured but not run on GitHub. |

Latest Maven verification: `mvn -o verify -B -DforkCount=0 -Dfailsafe.forkCount=0`; run completed successfully after the final source changes. Seven unit tests passed; 17 Testcontainers integration cases (HTTP API, PostgreSQL concurrency/lifecycle, Redis fallback, and RabbitMQ delivery) were discovered and skipped because no Docker runtime was found. No benchmark was measured.
