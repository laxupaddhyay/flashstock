# FlashStock — High-Concurrency Inventory Reservation Engine

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-Backend-brightgreen)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Database-blue)
![Redis](https://img.shields.io/badge/Redis-Caching-red)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-Messaging-orange)
![Docker](https://img.shields.io/badge/Docker-Containerization-blue)

**FlashStock** is a backend system designed to handle flash sales and high-concurrency inventory reservations without overselling products.

It simulates scenarios where thousands of users attempt to purchase limited-stock products simultaneously. The system uses database transactions, concurrency control, idempotent APIs, expiring reservations, and asynchronous event processing to maintain inventory correctness under heavy load.

The primary objective is to demonstrate how a production-style backend can maintain data integrity while handling concurrent requests, failures, and retries.

### Implementation status

The project now contains the Spring Boot application, PostgreSQL/Flyway schema, product and inventory APIs, the conditional-update reservation path, scoped idempotency, reservation lifecycle and orders, a `SKIP LOCKED` expiration worker, transactional outbox/RabbitMQ retry and deduplication, optional Redis product metadata caching, Actuator/Micrometer, Docker Compose, CI, OpenAPI, and k6 scripts. See the [architecture](docs/architecture.md) and [implementation plan](docs/implementation-plan.md). Unit tests run locally. PostgreSQL, Redis, RabbitMQ, and HTTP API Testcontainers integration tests are included but have not been run in this environment because Docker is unavailable. Performance results have not been measured; see [`docs/benchmark-results.md`](docs/benchmark-results.md).

---

## Table of Contents

- [Problem Statement](#problem-statement)
- [Key Features](#key-features)
- [Technology Stack](#technology-stack)
- [System Architecture](#system-architecture)
- [Core Design Decisions](#core-design-decisions)
- [Database Design](#database-design)
- [API Design](#api-design)
- [Reservation Lifecycle](#reservation-lifecycle)
- [Concurrency and Consistency](#concurrency-and-consistency)
- [Testing Strategy](#testing-strategy)
- [Performance Benchmarking](#performance-benchmarking)
- [Project Roadmap](#project-roadmap)
- [Project Structure](#project-structure)
- [Local Setup](#local-setup)
- [Future Improvements](#future-improvements)
- [Learning Outcomes](#learning-outcomes)

---

## Problem Statement

Imagine a flash sale where 100 units of a product are available and 10,000 users attempt to reserve them at approximately the same time.

A naive implementation might:

1. Read the available inventory.
2. Check whether sufficient stock exists.
3. Decrease the inventory.
4. Save the order.

When multiple requests execute this sequence concurrently, they may read the same stock value and make conflicting updates. This can result in overselling, inconsistent inventory, or duplicate orders.

FlashStock addresses these problems by implementing atomic inventory updates, transactional reservation workflows, idempotent request handling, and controlled asynchronous processing.

### Project goals

- Prevent inventory from becoming negative.
- Prevent concurrent reservations from exceeding available stock.
- Prevent duplicate reservations when clients retry requests.
- Release stock when reservations expire.
- Handle concurrent requests and transient service failures.
- Measure throughput, latency, and correctness under load.

## Key Features

### 1. Inventory Management

- Create and update products.
- Maintain total, reserved, and available inventory.
- Enforce inventory invariants at the database level.
- Track stock changes and reservation history.

### 2. High-Concurrency Reservations

- Reserve multiple units atomically.
- Handle simultaneous purchase attempts safely.
- Reject reservations when stock is insufficient.
- Use transactional database locking or conditional updates.
- Support configurable reservation expiration.

### 3. Idempotent APIs

- Accept an idempotency key for reservation requests.
- Return the original result for valid duplicate requests.
- Prevent retries from creating duplicate reservations.
- Reject reuse of an idempotency key with a different request payload.

### 4. Reservation Expiration

- Assign an expiration timestamp to each reservation.
- Automatically release stock from expired reservations.
- Handle payment and expiration races safely.
- Make expiration processing safe to retry.

### 5. Asynchronous Event Processing

- Publish reservation-created, reservation-expired, and order-confirmed events.
- Use RabbitMQ for asynchronous processing.
- Implement retry handling and dead-letter queues.
- Handle duplicate message delivery using idempotent consumers.

### 6. Observability

- Expose application metrics.
- Track request throughput and latency percentiles.
- Monitor failed reservations and stock contention.
- Use structured logs and correlation IDs.
- Visualize metrics through Prometheus and Grafana.

---

## Technology Stack

| Component | Technology | Purpose |
|---|---|---|
| Language | Java 21 | Core implementation |
| Backend | Spring Boot | REST APIs and application services |
| Database | PostgreSQL | Transactional inventory and reservation data |
| Database access | Spring Data JPA | Persistence and transaction management |
| Caching | Redis | Optional caching and short-lived coordination data |
| Messaging | RabbitMQ | Asynchronous event delivery |
| Security | Spring Security | Authentication and authorization |
| Migrations | Flyway | Versioned database schema changes |
| Testing | JUnit 5, Mockito | Unit and service tests |
| Integration testing | Testcontainers | Real PostgreSQL, Redis, and RabbitMQ dependencies |
| Load testing | k6 | Concurrent traffic simulation |
| Monitoring | Micrometer, Prometheus, Grafana | Metrics and dashboards |
| Containerization | Docker, Docker Compose | Reproducible local environment |
| CI | GitHub Actions | Automated build and test pipeline |

**Design principle:** PostgreSQL is the source of truth for inventory correctness. Redis and RabbitMQ must not be allowed to independently decide whether stock can be sold.

---

## System Architecture

```text
                    Clients / Load Generator
                              |
                              v
                     Spring Boot REST API
                              |
                  +-----------+-----------+
                  |                       |
                  v                       v
           Product Service        Reservation Service
                                          |
                                          v
                                    PostgreSQL
                               Inventory + Reservations
                                          |
                                          v
                                   Transactional
                                     Outbox
                                          |
                                          v
                                      RabbitMQ
                                          |
                            +-------------+-------------+
                            |                           |
                            v                           v
                     Order Processor             Event Consumer
                            |
                            v
                     Order Confirmation

        Redis: Optional caching and supporting coordination
        Prometheus + Grafana: Metrics and dashboards
```

The transactional outbox is part of the event path: each lifecycle transition commits its event record with its database changes. The relay publishes after commit and retries when RabbitMQ is unavailable. Delivery is at least once; consumer deduplication handles repeats.

### Architectural boundaries

- **Product Service:** Product metadata and inventory initialization.
- **Reservation Service:** Atomic stock reservation and expiration.
- **Order Processor:** Converts valid reservations into confirmed orders.
- **Event Publisher:** Delivers committed events to RabbitMQ.
- **Expiration Worker:** Identifies expired reservations and releases stock.
- **Observability:** Collects metrics, logs, and execution timings.

Start with a modular Spring Boot application rather than splitting everything into microservices immediately. Separate modules and clear service boundaries make the design easier to test and evolve.

---

## Core Design Decisions

### 1. Atomic inventory updates

Inventory must be updated atomically.

The default approach is a conditional SQL update:

```sql
UPDATE inventory
SET reserved_quantity = reserved_quantity + :quantity
WHERE product_id = :productId
  AND total_quantity - reserved_quantity >= :quantity;
```

Check the affected row count:

- One row updated: the reservation can proceed.
- Zero rows updated: the product does not have sufficient available stock.

Execute this update inside the reservation transaction. If any subsequent operation fails, roll back the transaction.

An optional `INVENTORY_RESERVATION_STRATEGY=row-lock` mode selects the inventory row with `SELECT ... FOR UPDATE`, checks available units, and then uses the same guarded update. The k6 comparison runner recreates the app with each strategy. Conditional SQL remains the default because it avoids an extra locked read. See [`docs/concurrency-control.md`](docs/concurrency-control.md) for details.

### 2. Transaction boundaries

A reservation should be created within a single database transaction that:

1. Checks the idempotency record.
2. Atomically reserves available inventory.
3. Creates the reservation.
4. Stores the idempotency result.
5. Records the event in the outbox, when implemented.

All database changes either commit together or roll back together.

Do not hold a database transaction open while waiting for an external payment service.

### 3. Redis usage

Use Redis for optional product metadata caching and other supporting features.

Do not rely solely on a Redis counter to guarantee inventory correctness. Cache eviction, expiration, failures, and competing updates can otherwise introduce inconsistencies.

### 4. RabbitMQ delivery semantics

Assume that messages can be delivered more than once.

Consumers should record processed event identifiers or use another durable idempotency mechanism. Acknowledge messages only after the relevant work has been safely committed.

A retry policy and dead-letter queue should make repeated failures observable rather than silently losing events.

---

## Database Design

### Products

| Column | Type | Description |
|---|---|---|
| id | UUID | Primary key |
| name | VARCHAR | Product name |
| sku | VARCHAR | Unique stock-keeping unit |
| price | NUMERIC | Product price |
| created_at | TIMESTAMPTZ | Creation time |

### Inventory

| Column | Type | Description |
|---|---|---|
| product_id | UUID | Primary key and product reference |
| total_quantity | BIGINT | Total stock |
| reserved_quantity | BIGINT | Stock currently reserved |
| updated_at | TIMESTAMPTZ | Last modification |

Constraints:

- `total_quantity >= 0`
- `reserved_quantity >= 0`
- `reserved_quantity <= total_quantity`

Available inventory is calculated as:

`available_quantity = total_quantity - reserved_quantity`

### Reservations

| Column | Type | Description |
|---|---|---|
| id | UUID | Reservation identifier |
| user_id | UUID | Customer identifier |
| product_id | UUID | Product reference |
| quantity | INTEGER | Reserved quantity |
| status | VARCHAR | Reservation lifecycle state |
| expires_at | TIMESTAMPTZ | Expiration time |
| created_at | TIMESTAMPTZ | Creation time |

Suggested statuses:

`PENDING`, `CONFIRMED`, `EXPIRED`, `CANCELLED`

### Idempotency Records

Store the idempotency key, request fingerprint, processing state, reservation reference, and response information.

Enforce uniqueness for the appropriate idempotency scope, such as `(user_id, idempotency_key)`.

### Orders

Store the confirmed order, its reservation reference, user, quantity, amount, and status. Add uniqueness constraints where needed to prevent a reservation from producing multiple orders.

### Outbox Events

Store an event identifier, aggregate identifier, event type, serialized payload, creation timestamp, and publication status.

This table allows a background publisher to retry events that were committed but not yet delivered.

---

## API Design

Use versioned REST endpoints under `/api/v1`.

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/products` | Create a product |
| GET | `/products/{id}` | Retrieve product details and availability |
| POST | `/products/{id}/inventory` | Initialize or adjust inventory |
| POST | `/reservations` | Create a reservation |
| GET | `/reservations/{id}` | Retrieve reservation status |
| POST | `/reservations/{id}/confirm` | Confirm a valid reservation |
| POST | `/reservations/{id}/cancel` | Cancel a reservation |
| GET | `/orders/{id}` | Retrieve order details |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Export application metrics |

### Example reservation request

```http
POST /api/v1/reservations
Idempotency-Key: 8bd39a6e-9e65-4e27-a0f1-8e5f
Content-Type: application/json
```

```json
{
  "productId": "d2e14c7b-5a61-4e32-9b82-98bbcc74a210",
  "quantity": 2
}
```

### Successful response

```json
{
  "reservationId": "a3c72b18-37ab-4d2b-93e4-21b8f7f8c620",
  "productId": "d2e14c7b-5a61-4e32-9b82-98bbcc74a210",
  "quantity": 2,
  "status": "PENDING",
  "expiresAt": "2026-10-14T18:35:00Z"
}
```

The example timestamp is illustrative.

### Error handling

Use meaningful HTTP status codes:

- `400 Bad Request`: Invalid quantity, malformed request, or invalid idempotency key.
- `401 Unauthorized`: Missing or invalid development credentials.
- `403 Forbidden`: An authenticated user attempts an operation they cannot access.
- `404 Not Found`: Product or reservation does not exist.
- `409 Conflict`: Insufficient stock or conflicting idempotency key.
- `422 Unprocessable Entity`: Invalid reservation state transition.
- `429 Too Many Requests`: Rate limit exceeded.
- `500 Internal Server Error`: Unexpected application failure.

Return a consistent error structure with a machine-readable error code and a useful message.

---

## Reservation Lifecycle

```text
                 Request Reservation
                         |
                         v
                  Validate Request
                         |
                         v
                  Check Idempotency
                         |
                         v
                 Reserve Stock Atomically
                         |
                +--------+--------+
                |                 |
             No Stock           Success
                |                 |
                v                 v
          Return Conflict    Create Reservation
                                  |
                                  v
                             PENDING
                                  |
                         +--------+--------+
                         |                 |
                    Confirmation        Expiration
                         |                 |
                         v                 v
                     CONFIRMED           EXPIRED
                         |                 |
                         v                 v
                   Create Order      Release Stock
```

### Important lifecycle rules

- A reservation can be confirmed only while it is valid and pending.
- Confirming an already confirmed reservation must not create another order.
- Expiration and confirmation must coordinate so that stock cannot be released after a successful confirmation.
- Cancellation and expiration must not release the same stock twice.
- Every successful stock release must correspond to a valid state transition.

Implement these transitions transactionally and test simultaneous confirmation and expiration requests.

---

## Concurrency and Consistency

This is the most important section of the project.

### Invariants

1. Available inventory is never negative.
2. Reserved inventory never exceeds total inventory.
3. Successful reservations never exceed the available stock.
4. A reservation releases stock at most once.
5. Repeated idempotent requests do not create duplicate reservations.
6. A reservation produces at most one confirmed order.

### Test scenario: Flash sale

Initial inventory: 100 units.

Concurrent reservation attempts: 10,000.

Each request attempts to reserve one unit.

Expected outcome:

- At most 100 requests successfully reserve inventory.
- The remaining requests fail due to insufficient stock, unless the test intentionally includes different request outcomes such as validation failures.
- Inventory never becomes negative.
- No duplicate reservation is created for a retried idempotent request.

The exact number of successful requests depends on the workload, but the correctness invariants must hold.

### Test scenario: Duplicate requests

Send multiple concurrent requests with the same idempotency key and identical payload.

Expected outcome:

- One logical reservation is created.
- Retries return the original result or an appropriate in-progress response.
- No additional stock is consumed.

### Test scenario: Reservation expiration

Create a reservation and allow it to expire.

Expected outcome:

- The reservation transitions to `EXPIRED` once.
- Reserved stock is released exactly once.
- The released stock can be reserved again.
- Repeated expiration processing does not increase available stock beyond the total.

---

## Testing Strategy

### Unit tests

Test business rules in isolation:

- Inventory availability calculations.
- Valid and invalid reservation transitions.
- Expiration rules.
- Idempotency key validation.
- Order confirmation rules.

### Integration tests

Use Testcontainers with PostgreSQL, Redis, and RabbitMQ.

Test actual database constraints, transaction rollbacks, message processing, and repository behavior.

### Concurrency tests

Use a controlled multithreaded test harness to submit concurrent requests against a shared database.

Validate both response outcomes and persisted state after all requests finish.

Include concurrent reservation, cancellation, confirmation, expiration, and duplicate-request scenarios.

### Failure injection

Test what happens when:

- A transaction fails after stock is reserved but before the reservation is committed.
- RabbitMQ is unavailable during event publication.
- A worker crashes after processing an event but before acknowledging it.
- A reservation expires while confirmation is being attempted.
- Redis becomes unavailable.
- A client retries after a timeout even though the original request committed.

Document the expected behavior for each failure before implementing the test.

---

## Performance Benchmarking

Use k6 to simulate flash-sale traffic with configurable virtual users, arrival rates, product quantities, and request distributions.

Record:

| Metric | Purpose |
|---|---|
| Requests per second | Throughput |
| p50 latency | Typical response time |
| p95 latency | High-percentile response time |
| p99 latency | Tail latency |
| HTTP error rate | Request failure rate |
| Successful reservations | Accepted demand |
| Rejected reservations | Requests that could not reserve stock |
| Database lock waits | Contention |
| CPU and memory | Resource utilization |

### Benchmark experiments

Compare:

1. Conditional atomic updates versus row-level locking.
2. Lower versus higher concurrency.
3. Single-product contention versus many independent products.
4. Different transaction and connection-pool settings.
5. Synchronous processing versus asynchronous event processing where applicable.

Record the hardware, database configuration, test duration, workload, and results so that measurements can be reproduced.

**Do not claim a throughput figure before measuring it.** The goal is to show the performance you achieved and explain the bottlenecks you discovered.

---

## Project Roadmap

### Phase 1 — Foundation

- [x] Create the Spring Boot project.
- [x] Configure PostgreSQL and Flyway.
- [x] Implement product and inventory entities.
- [x] Add schema constraints and basic APIs.
- [x] Add unit and Testcontainers integration tests (container-backed execution is pending Docker).

**Deliverable:** A working inventory service.

### Phase 2 — Atomic Reservations

- [x] Implement the reservation transaction.
- [x] Add atomic inventory updates and an optional row-lock comparison mode.
- [x] Implement reservation status transitions.
- [x] Add idempotency keys and database uniqueness constraints.
- [x] Add the 10,000-request persisted-state concurrency test (execution pending Docker).

**Deliverable:** An inventory engine that maintains its invariants under concurrency.

### Phase 3 — Expiration and Orders

- [x] Implement expiring reservations.
- [x] Build a `FOR UPDATE SKIP LOCKED` background expiration worker.
- [x] Implement cancellation and confirmation.
- [x] Add order creation and duplicate-order protection.
- [x] Add cancellation, confirmation, worker-repeat, rollback, and confirmation-versus-expiration integration tests (execution pending Docker).

**Deliverable:** A complete reservation lifecycle.

### Phase 4 — Asynchronous Processing

- [x] Configure durable RabbitMQ exchanges, queues, and bindings.
- [x] Publish reservation and order lifecycle events.
- [x] Implement consumer event-ID deduplication.
- [x] Add bounded retries and a dead-letter queue.
- [x] Implement the transactional outbox and publisher confirms.

**Deliverable:** Reliable asynchronous event processing.

### Phase 5 — Performance and Observability

- [x] Add Micrometer metrics.
- [x] Configure Prometheus and provision a Grafana dashboard.
- [x] Create configurable k6 flash-sale workloads and strategy runner.
- [ ] Benchmark concurrency and database contention (not measured in this environment).
- [ ] Identify and optimize measured bottlenecks.
- [x] Document the repeatable procedure; measured results remain pending.

**Deliverable:** A benchmarked backend with observable performance.

### Phase 6 — Production-Style Delivery

- [x] Dockerize the application and dependencies.
- [x] Add Docker Compose for local development.
- [x] Configure CI using GitHub Actions.
- [x] Add API documentation using OpenAPI.
- [x] Write architecture, schema, concurrency, failure, and benchmark documents.
- [x] Add local demo instructions (container startup is not verified here).

**Deliverable:** A portfolio-ready repository.

---

## Project Structure

```text
flashstock/
├── src/
│   ├── main/
│   │   ├── java/com/flashstock/
│   │   │   ├── product/
│   │   │   ├── inventory/
│   │   │   ├── reservation/
│   │   │   ├── order/
│   │   │   ├── idempotency/
│   │   │   ├── messaging/
│   │   │   ├── outbox/
│   │   │   ├── expiration/
│   │   │   ├── observability/
│   │   │   └── config/
│   │   └── resources/
│   │       ├── application.yml
│   │       └── db/migration/
│   └── test/
│       └── java/com/flashstock/
├── load-tests/
│   ├── flash-sale.js
│   └── compare-strategies.ps1
├── docs/
│   ├── implementation-plan.md
│   ├── architecture.md
│   ├── database-design.md
│   ├── concurrency-control.md
│   ├── failure-scenarios.md
│   └── benchmark-results.md
├── docker-compose.yml
├── Dockerfile
├── .github/workflows/
├── .env.example
├── .gitignore
├── pom.xml
└── README.md
```

Adjust the structure as the project evolves. Avoid creating empty modules simply to match the diagram.

---

## Local Setup

### Prerequisites

- Java 21
- Maven
- Docker and Docker Compose
- Git

### Configure and start the application

Copy the example environment file and replace the local placeholder passwords. Never commit `.env`.

```bash
cp .env.example .env
docker compose up --build -d
```

Compose starts PostgreSQL, Redis, RabbitMQ, the app, Prometheus, and Grafana. Flyway applies migrations during startup. The app refuses to start if `DEV_PASSWORD` is unset or still set to the example placeholder. Local Basic authentication uses `DEV_USER` and `DEV_PASSWORD`; this is for development only.

Check readiness, OpenAPI, Prometheus, and Grafana:

```bash
curl http://localhost:8080/actuator/health/readiness
# Open http://localhost:8080/swagger-ui.html
# Prometheus: http://localhost:9090
# Grafana: http://localhost:3000
```

Create a product with its initial stock (example):

```bash
curl -u "$DEV_USER:$DEV_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"name":"Demo item","sku":"DEMO-001","price":19.99,"initialQuantity":100}' \
  http://localhost:8080/api/v1/products
```

Reserve units using the returned `id` as `productId`:

```bash
curl -u "$DEV_USER:$DEV_PASSWORD" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-request-0001' \
  -d '{"productId":"<product-uuid>","quantity":2}' \
  http://localhost:8080/api/v1/reservations
```

The local development Basic identity scopes reservations and orders. Replace it with your identity provider and customer principal mapping before production.

Run tests:

```bash
mvn test
# Runs Testcontainers integration tests too (requires Docker)
mvn verify
```

Run a single k6 scenario after setting `DEV_PASSWORD` in the shell and choosing stocked `PRODUCT_ID`:

```bash
PRODUCT_ID=<product-uuid> DEV_PASSWORD=<local-password> k6 run load-tests/flash-sale.js
```

Compare the two inventory strategies from PowerShell 7; the script recreates the app with each strategy and creates a fresh benchmark product with the requested stock:

```powershell
$env:DEV_PASSWORD = "<local DEV_PASSWORD>"
./load-tests/compare-strategies.ps1 -Strategy conditional -Password $env:DEV_PASSWORD -Rate 100 -Duration 2m -Stock 100
./load-tests/compare-strategies.ps1 -Strategy row-lock -Password $env:DEV_PASSWORD -Rate 100 -Duration 2m -Stock 100
```

No benchmark figures have been produced yet. The Testcontainers concurrency test is a correctness test and not a performance claim. See [`docs/implementation-plan.md`](docs/implementation-plan.md) for verification status and [`docs/benchmark-results.md`](docs/benchmark-results.md) for measurement procedure.

---

## Future Improvements

- Multi-product cart reservations with deadlock prevention.
- Tenant-level inventory isolation.
- Waitlists for sold-out products.
- Distributed tracing using OpenTelemetry.
- Kubernetes deployment and autoscaling.
- Payment-provider integration using a sandbox.
- Inventory reconciliation jobs.
- Chaos testing and automated failure recovery.

Add these only after the core correctness and benchmarking requirements are satisfied.

---

## Learning Outcomes

By completing FlashStock, I aim to demonstrate practical understanding of:

- Java and Spring Boot backend engineering.
- PostgreSQL transactions, locking, and isolation.
- Race conditions and concurrent programming.
- Idempotency and safe retry semantics.
- Event-driven architecture with RabbitMQ.
- Reliable asynchronous processing and the transactional outbox pattern.
- Integration testing with real infrastructure.
- Load testing, observability, and performance optimization.
- Containerized development and CI automation.

## Success Criteria

FlashStock is considered complete when:

- Concurrent reservations never oversell inventory.
- Database constraints enforce critical inventory invariants.
- Retried requests do not create duplicate reservations or orders.
- Confirmation, cancellation, and expiration behave correctly under concurrency.
- Event processing recovers from duplicate delivery and transient failures.
- Integration tests and load tests are reproducible.
- Performance results and architectural trade-offs are documented.

**The central engineering objective is correctness under concurrency, supported by measurable performance and reproducible tests.**
