# FlashStock architecture

## Runtime shape

FlashStock is a single Spring Boot modular monolith. HTTP controllers handle request/response mapping; application services own use cases and transactions; Spring Data repositories and small JDBC stores perform persistence; PostgreSQL is authoritative. RabbitMQ carries asynchronous lifecycle notifications through a transactional outbox. Redis caches product metadata only; stock quantities are read fresh from PostgreSQL and every reserve/release is a PostgreSQL transaction.

```text
HTTP client --Basic auth--> Controllers --> Product / Reservation services
                                             |          |
                                             |          +-- atomic inventory SQL
                                             |          +-- idempotency row
                                             |          +-- reservation/order row
                                             |          +-- outbox row (same commit)
                                             v
                                         PostgreSQL
                                             |
                                  Outbox relay (confirm)
                                             |
                                             v
                 RabbitMQ durable lifecycle queue --> idempotent inbox receipt

Product metadata cache: Redis (best-effort; PostgreSQL fallback)
Operations: Actuator + Micrometer --> Prometheus --> provisioned Grafana dashboard
```

## Package responsibilities

| Package | Responsibility |
|---|---|
| `product` | Product DTOs, catalog persistence, validated stock adjustments, best-effort Redis metadata cache |
| `inventory` | Inventory mapping and atomic conditional writes; optional row-lock benchmark path |
| `reservation` | DTOs, owner-scoped commands, idempotency, lifecycle policy, reservation persistence |
| `idempotency` | Unique user/key claim, fingerprint compare, original reservation lookup |
| `order` | Immutable confirmation record, one order per reservation and owner-scoped retrieval |
| `expiration` | Batch expiration using `FOR UPDATE SKIP LOCKED` and exactly-once stock release per state transition |
| `outbox` | Event append to JSONB outbox within business transaction |
| `messaging` | Confirmed Rabbit publisher, durable topology, bounded retries/DLQ and processed-event inbox |
| `security` | Development-only externalized Basic user and authenticated customer identity |
| `observability` | Correlation ID, low-cardinality Micrometer metrics, backlog gauge |
| `api` | RFC 9457 `ProblemDetail` responses and stable error codes |

## Transaction boundaries

### Create reservation

One transaction inserts the scoped idempotency key using `ON CONFLICT DO NOTHING`, locks that key row, compares a SHA-256 fingerprint of canonical `(productId, quantity)`, and returns the existing reservation when present. For a new key, it conditionally increments `reserved_quantity` only when enough stock remains, then stores the pending reservation, links the idempotency record, and appends `ReservationCreated`. Any exception rolls back all of these changes, including the initial idempotency insert.

### Confirm, cancel, expire

Commands lock the reservation row. Exactly one of `PENDING -> CONFIRMED`, `PENDING -> CANCELLED`, or `PENDING -> EXPIRED` can win. Expiry timestamps and comparisons use PostgreSQL `clock_timestamp()` through `DatabaseClock`, avoiding inconsistent host clocks across application instances. Confirmation decrements both total and reserved quantities (the sale has consumed units), writes the unique order, and appends an event. Cancellation/expiration decrement reserved only and append one lifecycle event. A duplicate cancellation/expiration sees terminal state and does not update stock. An already-confirmed request returns its existing unique order.

### Outbox relay

The publisher reads due unconfirmed rows without holding a database transaction while calling RabbitMQ. It waits for publisher confirms, then marks the row sent. A crash after broker confirmation and before marking sent can publish a duplicate; this is deliberate at-least-once delivery. The durable consumer inbox uses `(consumer_name,event_id)` to deduplicate. Consumer work and inbox receipt must share a transaction when adding future business side effects.

## API

All business endpoints require the development Basic user unless replaced by production identity integration. Customer IDs are derived from the configured development username and only scope local demo ownership; replace this authentication configuration with an identity provider in production.

| Method | Path | Contract |
|---|---|---|
| `POST` | `/api/v1/products` | `name`, `sku`, decimal `price`, nonnegative `initialQuantity`; returns product and inventory |
| `GET` | `/api/v1/products/{id}` | Catalog metadata plus current PostgreSQL stock totals |
| `POST` | `/api/v1/products/{id}/inventory` | `{ "direction": "INCREASE"|"DECREASE", "quantity": positiveLong }`; decreases cannot go below reserved |
| `POST` | `/api/v1/reservations` | `Idempotency-Key` header plus `productId`, positive `quantity`; owner is authenticated principal |
| `GET` | `/api/v1/reservations/{id}` | Owner-scoped reservation state |
| `POST` | `/api/v1/reservations/{id}/confirm` | Owner-scoped, idempotent order confirmation; expired result returns `EXPIRED` and null order |
| `POST` | `/api/v1/reservations/{id}/cancel` | Owner-scoped; terminal cancel/expire repeats return terminal reservation |
| `GET` | `/api/v1/orders/{id}` | Owner-scoped order lookup; unknown and other-owner orders both return 404 |
| `GET` | `/actuator/health/readiness` | PostgreSQL readiness (Redis is a best-effort cache dependency) |
| `GET` | `/actuator/prometheus` | Prometheus scrape endpoint |
| `GET` | `/swagger-ui.html` | OpenAPI UI |

Errors are RFC 9457 `application/problem+json` with a stable `code`: validation 400, unauthenticated 401, forbidden 403, not found 404, stock/idempotency conflict 409, invalid transition 422, unexpected 500. Basic-auth challenges return a problem body. User and resource IDs are never metric tags.

## Design decisions

- **Conditional update first:** database predicate and stock mutation are one serialized write. The configurable `row-lock` mode first obtains `SELECT ... FOR UPDATE`, checks available quantity, then executes the same guarded update; it exists to compare contention behavior, not as the default.
- **No distributed inventory lock:** Redis is unsuitable as the stock authority because loss/eviction would break correctness.
- **No external calls in business transactions:** Rabbit publishing occurs from committed outbox rows and cache use occurs only on untransactional product reads.
- **At-least-once messaging:** publisher confirms narrow delivery uncertainty but do not make the database and RabbitMQ one atomic system. Stable event IDs and inbox uniqueness make redelivery safe.
- **Single-product reservation:** each reservation has one product and quantity. Multi-line carts need explicit lock ordering and all-or-nothing semantics and are out of scope.
- **Money:** PostgreSQL `NUMERIC(19,4)` and Java `BigDecimal`; no binary floating-point money.
- **Development authentication:** one user/password is supplied by environment, password encoded with BCrypt. No default password starts successfully. Replace with production SSO/JWT and role policy before deployment.

## Operations and metrics

The app exports standard HTTP request histograms and Hikari connection-pool meters, `flashstock.reservations{outcome}`, `flashstock.expiration.processed`, `flashstock.outbox.publish{outcome}`, `flashstock.messaging.delivery{outcome}`, and `flashstock.outbox.backlog`. Tags use bounded outcomes only. `X-Correlation-ID` accepts a sanitized caller value or generates a UUID; it is placed in MDC and echoed in the response. No request identifier is a metric label.

Readiness requires PostgreSQL. Redis read/write failures fall back to PostgreSQL and produce a warning; cache is not used for quantities. RabbitMQ outage leaves events pending for retry, so business writes remain available as long as PostgreSQL is healthy. Monitor outbox backlog and the dead-letter queue.

## Source layout

```text
src/main/java/com/flashstock/{api,config,expiration,idempotency,inventory,messaging,observability,order,outbox,product,reservation,security}
src/main/resources/application.yml
src/main/resources/db/migration/V1__flashstock_schema.sql
src/test/java/                         unit and Testcontainers integration tests
load-tests/                            k6 flash-sale workload and comparison runner
monitoring/                            Prometheus target and provisioned Grafana dashboard
docs/                                  implementation, schema, failure, concurrency, benchmark notes
```
