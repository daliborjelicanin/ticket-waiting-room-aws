-- Stands in for a service's schema: a table, and a sequence behind it (bigserial), so the
-- test proves the role can insert, which needs the sequence grant as well as the table's.
CREATE TABLE things (
    id   BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL
);
