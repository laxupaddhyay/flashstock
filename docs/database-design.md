# Database design

PostgreSQL is the correctness boundary. Flyway migration `V1__flashstock_schema.sql` creates all core tables. Hibernate runs with `ddl-auto: validate`; schema changes must be new Flyway migrations, never implicit DDL.

## Tables and integrity rules

### `products`

UUID primary key, display name, unique SKU, `NUMERIC(19,4)` price, creation time. `uq_products_sku` prevents duplicate catalog identifiers; `ck_products_price_nonnegative` protects stored price.

### `inventory`

One row per product, with FK `product_id`, nonnegative `BIGINT` total/reserved and update time. Check constraints enforce `total_quantity >= 0`, `reserved_quantity >= 0`, and `reserved_quantity <= total_quantity`. Available quantity is derived, never stored: `total_quantity - reserved_quantity`.

### `reservations`

UUID, customer UUID, product FK, positive integer quantity, constrained state (`PENDING`, `CONFIRMED`, `EXPIRED`, `CANCELLED`), expiration and creation/update timestamps. Partial `ix_reservations_expiration` contains only pending reservations and orders by expiration/id for worker batches. `ix_reservations_owner_created` supports customer history access if a list API is added.

### `idempotency_records`

Composite primary key `(user_id,idempotency_key)` scopes retries by authenticated customer. Stores a 64-character SHA-256 request fingerprint and nullable reservation reference while the row is being created in the same transaction. Because the key claim and reservation result commit together, the nullable intermediate state is not normally visible after commit. A different fingerprint is a 409. A rolled-back request leaves no claim.

### `orders`

One row for each confirmed reservation. `uq_orders_reservation` is the final database-level guarantee that one reservation cannot make multiple orders. The row snapshots product ID, quantity, decimal unit/total price, customer and confirmation state. `ix_orders_owner_created` supports customer order history.

### `outbox_events`

UUID event ID, aggregate UUID, type, JSONB payload, enqueue/publish timestamps, attempt count, next retry timestamp, and a bounded diagnostic string. Partial `ix_outbox_pending` supports due unpublished event scans without indexing published history.

### `processed_events`

Composite primary key `(consumer_name,event_id)` is the durable inbox deduplication boundary. A redelivered event cannot add a second receipt for the same consumer.

## Query and transaction details

- Reserve: conditional `UPDATE inventory ... WHERE total_quantity - reserved_quantity >= :quantity`; check row count equals one.
- Consume on order confirmation: decrement both `total_quantity` and `reserved_quantity` in one guarded update.
- Release on cancellation or expiration: decrement `reserved_quantity` guarded by sufficient reserved stock.
- Inventory decrease: only when unreserved (`total_quantity - reserved_quantity >= :quantity`).
- Expiration work: ordered pending selection with `FOR UPDATE SKIP LOCKED` and bounded batch size. State transition, stock release, and event insert share one transaction.
- Outbox relay: read due rows, publish/confirm outside database transactions, then mark published. Duplicate publication is safe and expected on uncertain relay crashes.

The schema intentionally does not persist `available_quantity`: doing so would duplicate state and invite divergence. Prices are snapshots in orders; payment is not integrated.
