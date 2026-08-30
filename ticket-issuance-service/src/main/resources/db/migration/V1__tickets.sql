-- Tickets: the artefact of a paid order, and the reason this service exists.
--
-- A separate database from `checkout` on the same instance, not a table in it. The two are
-- separate bounded contexts with separate deployables and separate Flyway histories, and
-- there is therefore deliberately NO foreign key to orders(id) below. That is a real
-- tradeoff, stated rather than hidden: the database can no longer refuse a ticket for a
-- non-existent order. The saga is what guarantees the order exists — nothing enqueues an
-- issuance message until a payment has been captured against it.

CREATE TABLE tickets (
    id            uuid        PRIMARY KEY,
    order_id      uuid        NOT NULL,
    event_id      text        NOT NULL,
    seat_id       text        NOT NULL,
    customer_ref  text        NOT NULL,
    -- The gateway's reference for the capture that paid for this seat. Carried so a ticket
    -- can be traced back to money without a join across two databases.
    gateway_ref   text,
    -- Derived from id (see TicketQrObjects), stored anyway: the download endpoint must not
    -- have to re-derive a key that a future prefix change would silently invalidate for
    -- every ticket already written.
    qr_object_key text        NOT NULL,
    issued_at     timestamptz NOT NULL,

    -- The invariant this whole slice rests on.
    --
    -- SQS Standard is at-least-once, and the saga retries EnqueueTicketIssuance up to four
    -- times. Duplicate delivery is therefore not an edge case to defend against, it is
    -- ordinary operation, and "issue exactly one ticket per seat per order" has to survive
    -- two consumer tasks processing two copies of the same message at the same instant.
    --
    -- Declared here rather than as a findByOrderIdAndSeatId check in the service for the
    -- same reason the payments constraints are: a read-then-write in application code is
    -- two statements with a race between them, and it only protects against writers that
    -- remember to call it. This holds against every writer, including one nobody has
    -- written yet. The consumer's idempotency is literally this line.
    CONSTRAINT uq_tickets_order_seat UNIQUE (order_id, seat_id)
);

CREATE INDEX ix_tickets_order_id ON tickets (order_id);
