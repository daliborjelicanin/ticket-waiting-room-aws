-- Atomically admit the next N tokens from the head of the queue.
--
-- ZPOPMIN alone is already atomic, so two concurrent admitters can never pop the same
-- token. The reason this is a script is the *move*: popping and recording the admission
-- are two commands, and a token dropped between them would vanish from the queue without
-- ever being admitted. Inside a script the pair cannot interleave or half-apply.
--
-- KEYS[1] queue zset
-- KEYS[2] admitted zset
-- KEYS[3] sequence counter
-- ARGV[1] how many to admit
-- ARGV[2] admission-pass expiry for this batch, epoch seconds
-- ARGV[3] now, epoch seconds
-- ARGV[4] key ttl, seconds
--
-- Returns the admitted tokens, in admission order.

local queueKey, admittedKey, seqKey = KEYS[1], KEYS[2], KEYS[3]
local count = tonumber(ARGV[1])
local expiresAt = ARGV[2]
local now = ARGV[3]
local keyTtl = tonumber(ARGV[4])

-- Reclaim the slots of anyone whose pass already lapsed, before handing out new ones.
redis.call('ZREMRANGEBYSCORE', admittedKey, '-inf', now)

-- ZPOPMIN pops lowest-score-first, and the score is the INCR ticket number, so the head
-- of the queue is always the earliest joiner. This is the FIFO admission guarantee.
local popped = redis.call('ZPOPMIN', queueKey, count)

local admitted = {}
for i = 1, #popped, 2 do
    admitted[#admitted + 1] = popped[i]
    redis.call('ZADD', admittedKey, expiresAt, popped[i])
end

redis.call('EXPIRE', queueKey, keyTtl)
redis.call('EXPIRE', seqKey, keyTtl)
redis.call('EXPIRE', admittedKey, keyTtl)

return admitted
