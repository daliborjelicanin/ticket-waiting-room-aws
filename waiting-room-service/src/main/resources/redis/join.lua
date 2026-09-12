-- Idempotent FIFO enqueue of one waiting-room place, bound to one identity.
--
-- KEYS[1] queue zset      (token -> ticket number)
-- KEYS[2] sequence counter
-- KEYS[3] admitted zset   (token -> admission-pass expiry, epoch seconds)
-- KEYS[4] identity hash   (customer -> token)
-- ARGV[1] customer, the authenticated caller
-- ARGV[2] candidate token, used only if this customer has never joined this event
-- ARGV[3] now, epoch seconds
-- ARGV[4] key ttl, seconds
--
-- Returns { status, position, queueLength, admissionExpiresAt, token }, all as strings;
-- position and admissionExpiresAt are '0' when they do not apply.

local queueKey, seqKey, admittedKey, identityKey = KEYS[1], KEYS[2], KEYS[3], KEYS[4]
local customer = ARGV[1]
local candidateToken = ARGV[2]
local now = tonumber(ARGV[3])
local keyTtl = tonumber(ARGV[4])

-- All four keys always get one shared deadline, so an idle event's queue, counter,
-- admissions and identity map lapse together. A counter that outlived its queue would
-- restart ticket numbers and let a late joiner outrank everyone already waiting; an
-- identity map that lapsed early would hand a waiting customer a second token.
local function touch()
    redis.call('EXPIRE', queueKey, keyTtl)
    redis.call('EXPIRE', seqKey, keyTtl)
    redis.call('EXPIRE', admittedKey, keyTtl)
    redis.call('EXPIRE', identityKey, keyTtl)
end

-- One live place per identity, and this single line is what enforces it.
--
-- HSETNX either claims the candidate token for a first-time joiner or reports that this
-- customer already holds one, in which case the candidate is discarded and the existing
-- token is used for everything below. Declared in Redis rather than checked in Java for
-- the same reason the seat hold is a conditional write and at most one live payment per
-- order is a partial unique index: a read-then-write in application code is two statements
-- with a race between them, and here the race is a person holding two places in line.
--
-- Without this, FIFO ordering is a guarantee about tokens rather than about people: one
-- actor can join ten thousand times, be ordered perfectly fairly, and take the room.
local token = candidateToken
if redis.call('HSETNX', identityKey, customer, candidateToken) == 0 then
    token = redis.call('HGET', identityKey, customer)
end

local admittedUntil = redis.call('ZSCORE', admittedKey, token)
if admittedUntil then
    if tonumber(admittedUntil) > now then
        return { 'ADMITTED', '0', tostring(redis.call('ZCARD', queueKey)), admittedUntil, token }
    end
    -- The pass lapsed, so the slot is forfeit and this customer queues again from the
    -- back. The identity mapping deliberately survives: the person is the same person,
    -- and re-issuing a token here would let a lapsed pass buy a second place.
    redis.call('ZREM', admittedKey, token)
end

-- Re-joining must never move an existing token: a client retrying a join whose response
-- was lost would otherwise surrender its place to everyone who arrived in between. Note
-- the client no longer has to keep anything for this to hold — idempotency now rests on
-- who the caller is, not on a token they were trusted to remember.
local score = redis.call('ZSCORE', queueKey, token)
if not score then
    -- INCR is the fairness primitive: an atomic, monotonic ticket number. Scoring by
    -- wall clock instead would tie for joins landing in the same millisecond and order
    -- them arbitrarily -- exactly the guarantee this queue exists to provide.
    redis.call('ZADD', queueKey, redis.call('INCR', seqKey), token)
end

touch()
return { 'WAITING', tostring(redis.call('ZRANK', queueKey, token) + 1),
         tostring(redis.call('ZCARD', queueKey)), '0', token }
