CREATE TABLE products (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    sku VARCHAR(80) NOT NULL,
    price NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_price_nonnegative CHECK (price >= 0)
);

CREATE TABLE inventory (
    product_id UUID PRIMARY KEY REFERENCES products(id) ON DELETE RESTRICT,
    total_quantity BIGINT NOT NULL,
    reserved_quantity BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_inventory_total_nonnegative CHECK (total_quantity >= 0),
    CONSTRAINT ck_inventory_reserved_nonnegative CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_inventory_reserved_lte_total CHECK (reserved_quantity <= total_quantity)
);

CREATE TABLE reservations (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_reservations_status CHECK (status IN ('PENDING','CONFIRMED','EXPIRED','CANCELLED'))
);
CREATE INDEX ix_reservations_expiration ON reservations(expires_at, id) WHERE status = 'PENDING';
CREATE INDEX ix_reservations_owner_created ON reservations(user_id, created_at DESC, id);

CREATE TABLE idempotency_records (
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    reservation_id UUID REFERENCES reservations(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key)
);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    reservation_id UUID NOT NULL REFERENCES reservations(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL,
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(19, 4) NOT NULL,
    total_amount NUMERIC(19, 4) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_reservation UNIQUE (reservation_id),
    CONSTRAINT ck_orders_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_orders_amount_nonnegative CHECK (unit_price >= 0 AND total_amount >= 0),
    CONSTRAINT ck_orders_status CHECK (status IN ('CONFIRMED'))
);
CREATE INDEX ix_orders_owner_created ON orders(user_id, created_at DESC, id);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error VARCHAR(500),
    CONSTRAINT ck_outbox_attempts_nonnegative CHECK (attempts >= 0)
);
CREATE INDEX ix_outbox_pending ON outbox_events(next_attempt_at, created_at, id) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    consumer_name VARCHAR(120) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);
