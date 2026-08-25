-- Orders and payments: the data that needs real ACID transactions.
--
-- The split from DynamoDB is deliberate. Seat inventory is a single-item, high-contention
-- race that conditional writes settle. What lives here is different: money, and invariants
-- that span *rows* rather than sitting inside one. Those invariants are declared at the
-- bottom of this file as constraints, so they hold even when application code is wrong.

CREATE TABLE orders (
    id              uuid        PRIMARY KEY,
    event_id        text        NOT NULL,
    customer_ref    text        NOT NULL,
    status          text        NOT NULL,
    -- Money is integer minor units. A float amount is a rounding bug waiting to be found
    -- by an auditor, and NUMERIC would invite arithmetic this service has no business doing.
    amount_cents    bigint      NOT NULL,
    currency        varchar(3)  NOT NULL,
    -- Caller-supplied. Makes order creation idempotent: a retried create returns the
    -- original order instead of a duplicate the customer might pay for twice.
    idempotency_key text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,

    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_orders_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED'))
);

-- The seats this order is for, with the hold that reserved each one. The hold id is what
-- the saga's compensating action needs: on payment failure it releases exactly these holds.
CREATE TABLE order_seats (
    order_id uuid NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    seat_id  text NOT NULL,
    hold_id  text NOT NULL,

    PRIMARY KEY (order_id, seat_id)
);

CREATE TABLE payments (
    id              uuid        PRIMARY KEY,
    order_id        uuid        NOT NULL REFERENCES orders (id),
    status          text        NOT NULL,
    amount_cents    bigint      NOT NULL,
    currency        varchar(3)  NOT NULL,
    payment_token   text        NOT NULL,
    gateway_ref     text,
    failure_reason  text,
    idempotency_key text        NOT NULL,
    created_at      timestamptz NOT NULL,
    completed_at    timestamptz,

    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'CAPTURED', 'DECLINED', 'FAILED'))
);

CREATE INDEX ix_payments_order_id ON payments (order_id);

-- The invariant that justifies Postgres for this data.
--
-- At most one *live* payment attempt per order, where live means in flight (PENDING) or
-- succeeded (CAPTURED). Two consequences, both of which are the difference between a bug
-- and a customer charged twice:
--
--   * Concurrent attempts with different idempotency keys cannot both reach the gateway.
--     The loser fails this index at insert time, before any money moves.
--   * A captured payment permanently blocks further attempts on that order.
--
-- DECLINED and FAILED sit outside the predicate on purpose, leaving room for a
-- retry-with-another-card flow. Note that the current order lifecycle does not use that
-- room: a decline moves the order to FAILED so the saga releases the seats, and the order
-- status guard then refuses further attempts. The predicate is written for where this is
-- going, and is defence in depth meanwhile.
--
-- Expressing "at most one row per group matching a predicate" is exactly what a partial
-- unique index is for, and exactly what a key-value store cannot declare -- there the rule
-- would have to be reimplemented, correctly, in every writer.
CREATE UNIQUE INDEX ux_payments_one_live_per_order
    ON payments (order_id)
    WHERE status IN ('PENDING', 'CAPTURED');
