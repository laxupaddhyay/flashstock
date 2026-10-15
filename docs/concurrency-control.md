# Concurrency control

## Invariants

1. `total_quantity >= 0`.
2. `reserved_quantity >= 0`.
3. `reserved_quantity <= total_quantity`.
4. Successful reservations increment reserved stock only if sufficient availability exists at the instant of the update.
5. One pending-to-terminal state transition can release stock once.
6. One reservation produces at most one order.
7. Same idempotency key and fingerprint returns its original reservation without another stock update.

The first three are PostgreSQL CHECK constraints. The remaining rules combine conditional SQL, row locks, a transaction, and uniqueness constraints.

## Conditional update (default)

```sql
UPDATE inventory
SET reserved_quantity = reserved_quantity + :quantity,
    updated_at = now()
WHERE product_id = :productId
  AND total_quantity - reserved_quantity >= :quantity;
```

The stock predicate and mutation execute as one PostgreSQL statement. Concurrent updates to the same inventory row serialize. An affected count of one means reserved; zero means no product row or insufficient available stock (the service distinguishes nonexistent product by checking product first). Reservation insert, idempotency record, and outbox event are in that same transaction. Any later failure rolls the stock write back.

Inventory increase/decrease, sale consumption, and reservation release use their own guarded atomic update statements and verify the affected-row count. No code uses a read-then-write check to authorize stock.

## Row-lock comparison mode

Set `INVENTORY_RESERVATION_STRATEGY=row-lock` before starting the app. This mode locks the inventory row with JPA `PESSIMISTIC_WRITE`, checks its current derived availability, and applies the same guarded update. It is intentionally the same business operation and result semantics as the default, with an extra locked read to make the cost of an explicit row-lock path measurable.

Both strategies serialize hot single-product updates in PostgreSQL. Conditional update minimizes round trips and keeps the predicate closest to the write. Explicit locking makes it easier to perform several dependent inventory decisions under one lock, but increases query/lock-hold work. Neither strategy permits overselling. Actual throughput and tail latency depend on PostgreSQL, connection-pool size, CPU and contention and must be measured rather than inferred. Expiry comparisons use database `clock_timestamp()` after locking, so application instance clock skew cannot decide whether a reservation wins confirmation or expiration.

## Reservation state races

Confirm/cancel obtains `SELECT ... FOR UPDATE` on one reservation row. Expiration selects rows via `FOR UPDATE SKIP LOCKED`. Both approaches serialize on the same row. Expiry comparisons use the database clock after the lock is acquired. After acquiring it, a command checks current status and expiry, then applies the status transition and exactly one inventory effect inside the transaction:

- confirm before expiry: pending -> confirmed; reduce total and reserved; insert unique order;
- confirm at/after expiry: pending -> expired; release reserved; no order;
- cancel pending: pending -> cancelled; release reserved;
- expiration worker: pending/expired-time -> expired; release reserved;
- terminal retries do not modify inventory.

If a worker crashes before commit, PostgreSQL rolls back the transition and stock update; another batch can claim it. If it crashes after commit, the terminal status prevents another release. `SKIP LOCKED` lets multiple instances work separate rows without waiting on an already claimed reservation.

## Idempotency contention

The unique `(user_id,idempotency_key)` insert serializes duplicate claims. `SELECT ... FOR UPDATE` then compares canonical SHA-256 of `productId:quantity`. Same payload returns the stored reservation; another payload returns conflict. The temporary record cannot commit without its reservation reference in the same transaction. This handles simultaneous retries without a separate distributed lock.

## Reproducible correctness test

`FlashSaleConcurrencyIT` starts PostgreSQL Testcontainers, seeds exactly 100 stock, and issues 10,000 valid independent one-unit reservation attempts with a 48-thread pool and a start barrier. It asserts exactly 100 success outcomes, 9,900 insufficient-stock outcomes, and persisted total/reserved/available quantities of 100/100/0. This is a correctness scenario, not a benchmark result; record hardware/runtime separately before making performance claims.
