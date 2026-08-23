-- Idempotent FIFO enqueue of one waiting-room token.
--
-- KEYS[1] queue zset      (token -> ticket number)
-- KEYS[2] sequence counter
-- KEYS[3] admitted zset   (token -> admission-pass expiry, epoch seconds)
-- ARGV[1] token
-- ARGV[2] now, epoch seconds
-- ARGV[3] key ttl, seconds
--
-- Returns { status, position, queueLength, admissionExpiresAt }, all as strings;
-- position and admissionExpiresAt are '0' when they do not apply.

local queueKey, seqKey, admittedKey = KEYS[1], KEYS[2], KEYS[3]
local token = ARGV[1]
local now = tonumber(ARGV[2])
local keyTtl = tonumber(ARGV[3])

-- All three keys always get one shared deadline, so an idle event's queue, counter and
-- admissions lapse together. A counter that outlived its queue would restart ticket
-- numbers and let a late joiner outrank everyone already waiting.
local function touch()
    redis.call('EXPIRE', queueKey, keyTtl)
    redis.call('EXPIRE', seqKey, keyTtl)
    redis.call('EXPIRE', admittedKey, keyTtl)
end

local admittedUntil = redis.call('ZSCORE', admittedKey, token)
if admittedUntil then
    if tonumber(admittedUntil) > now then
        return { 'ADMITTED', '0', tostring(redis.call('ZCARD', queueKey)), admittedUntil }
    end
    -- The pass lapsed, so the slot is forfeit and this token queues again from the back.
    redis.call('ZREM', admittedKey, token)
end

-- Re-joining must never move an existing token: a client retrying a join whose response
-- was lost would otherwise surrender its place to everyone who arrived in between.
local score = redis.call('ZSCORE', queueKey, token)
if not score then
    -- INCR is the fairness primitive: an atomic, monotonic ticket number. Scoring by
    -- wall clock instead would tie for joins landing in the same millisecond and order
    -- them arbitrarily -- exactly the guarantee this queue exists to provide.
    redis.call('ZADD', queueKey, redis.call('INCR', seqKey), token)
end

touch()
return { 'WAITING', tostring(redis.call('ZRANK', queueKey, token) + 1),
         tostring(redis.call('ZCARD', queueKey)), '0' }
