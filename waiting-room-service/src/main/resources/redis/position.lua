-- Read-only status lookup for one customer. Takes its counts in a single script so
-- position and queue length are a consistent snapshot rather than two reads straddling
-- an admission.
--
-- Looked up by identity rather than by token, which is what removes an entire class of
-- request: there is no longer a token in the URL for one client to poll on another's
-- behalf.
--
-- KEYS[1] queue zset
-- KEYS[2] admitted zset
-- KEYS[3] identity hash   (customer -> token)
-- ARGV[1] customer, the authenticated caller
-- ARGV[2] now, epoch seconds
--
-- Returns { status, position, queueLength, admissionExpiresAt, token }, all as strings.
-- The token is '' when this customer holds none: a genuine nil would truncate the reply
-- array on the way out of Lua.

local queueKey, admittedKey, identityKey = KEYS[1], KEYS[2], KEYS[3]
local customer = ARGV[1]
local now = tonumber(ARGV[2])

local queueLength = tostring(redis.call('ZCARD', queueKey))

local token = redis.call('HGET', identityKey, customer)
if not token then
    return { 'NOT_QUEUED', '0', queueLength, '0', '' }
end

local admittedUntil = redis.call('ZSCORE', admittedKey, token)
if admittedUntil then
    -- A lapsed pass is reported rather than deleted: reclaiming it is admit.lua's job,
    -- which keeps this path free of writes.
    local status = 'EXPIRED'
    if tonumber(admittedUntil) > now then
        status = 'ADMITTED'
    end
    return { status, '0', queueLength, admittedUntil, token }
end

local rank = redis.call('ZRANK', queueKey, token)
if not rank then
    -- Known customer, but the token is in neither structure: the pass lapsed and a later
    -- admit reclaimed it. The identity mapping outlives that on purpose, so this is the
    -- ordinary "your slot was forfeited" answer rather than an unknown caller.
    return { 'NOT_QUEUED', '0', queueLength, '0', token }
end
return { 'WAITING', tostring(rank + 1), queueLength, '0', token }
