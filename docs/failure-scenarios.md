# Failure scenarios and recovery

| Failure | Expected behavior |
|---|---|
| Insufficient inventory | Conditional update affects zero rows; 409 `INSUFFICIENT_STOCK`; transaction rolls back the idempotency claim; no reservation or event. |
| Duplicate key, same payload | Unique insert waits for the competing transaction; then returns the same reservation ID without touching stock. |
| Duplicate key, different payload | Fingerprint mismatch returns 409. Existing key and stock remain unchanged. |
| Failure after inventory update | PostgreSQL rolls back inventory, reservation, idempotency completion and outbox insert together. Integration test injects an exception after update. |
| Duplicate concurrent cancel | Reservation row lock serializes calls. First pending transition releases stock; later terminal call returns existing state without a second decrement. |
| Confirmation vs expiration | Both lock/claim same reservation. Exactly one pending transition wins; confirmed creates one order and consumes stock, expired releases held stock. |
| Worker process stops before commit | DB transaction and row locks roll back; next worker can claim still-pending rows. |
| Worker stops after commit | `EXPIRED` status is durable and excluded from the pending partial index; repeat processing does nothing. |
| RabbitMQ unavailable | Reservation/order transaction still commits to PostgreSQL outbox. Relay records bounded attempt metadata and backs off to at most 300 seconds. |
| Broker confirm followed by relay crash | Outbox row can be sent again because publish and DB marking are not atomic. Stable event ID plus consumer inbox uniqueness makes repeat delivery idempotent. |
| Consumer transient failure | Listener retries up to three attempts with bounded exponential backoff, then rejects without requeue; queue dead-letters to durable dead-letter queue for inspection. |
| Duplicate event | `processed_events` unique `(consumer_name,event_id)` permits one receipt. Any future side effects must join the same DB transaction as this insert. |
| Redis unavailable/cache corrupt | Cache read/write logs a generic warning and product metadata loads from PostgreSQL. Inventory always comes from PostgreSQL. Redis is excluded from readiness. |
| PostgreSQL unavailable | Readiness is down and requests fail; no Redis/Rabbit path can accept reservations without the source of truth. |
| Invalid Basic credentials | 401 RFC 9457 problem response; customer-only reads use owner-scoped lookup and do not disclose another user's object. |

The only delivery promise for RabbitMQ is at least once. Event consumers must tolerate duplicates; no exactly-once broker claim is made.
