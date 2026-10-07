-- Refunds: the saga's compensation once money has moved.
--
-- Before this migration a payment that captured and then could not be matched to a sold seat
-- ended in a Fail state named PaidButNotConfirmed, with the money kept. A refund is the
-- compensating action for a capture in the same way a hold release is for a hold, so it gets
-- the same treatment as the charge: a row claimed and committed before the gateway is called,
-- an idempotency key the saga's retries replay, and the "no double refund" rule declared here
-- as a constraint rather than checked in Java.

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status
    CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'REFUNDED'));

CREATE TABLE refunds (
    id              uuid        PRIMARY KEY,
    order_id        uuid        NOT NULL REFERENCES orders (id),
    -- The capture being reversed. A refund is always of one payment, never of an order in
    -- the abstract, because the gateway refunds a charge by its reference.
    payment_id      uuid        NOT NULL REFERENCES payments (id),
    status          text        NOT NULL,
    amount_cents    bigint      NOT NULL,
    currency        varchar(3)  NOT NULL,
    reason          text        NOT NULL,
    gateway_ref     text,
    failure_reason  text,
    idempotency_key text        NOT NULL,
    created_at      timestamptz NOT NULL,
    completed_at    timestamptz,

    CONSTRAINT uq_refunds_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_refunds_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX ix_refunds_order_id ON refunds (order_id);

-- At most one live refund per payment: in flight (PENDING) or done (SUCCEEDED).
--
-- The same shape as ux_payments_one_live_per_order and for the same reason. Concurrent
-- refunds with different keys cannot both reach the gateway, and a refunded payment can
-- never be refunded again. Refunds are always for the full captured amount, so "one live
-- refund" and "never refund more than was taken" are the same rule. A partial-refund flow
-- would need a different constraint, because the rule would become a sum.
--
-- FAILED sits outside the predicate, so a refund the gateway could not complete can be
-- retried under a new key by an operator.
CREATE UNIQUE INDEX ux_refunds_one_live_per_payment
    ON refunds (payment_id)
    WHERE status IN ('PENDING', 'SUCCEEDED');
