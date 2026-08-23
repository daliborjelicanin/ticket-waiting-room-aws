-- Read-only status lookup for one waiting-room token. Takes its counts in a single
-- script so position and queue length are a consistent snapshot rather than two reads
-- straddling an admission.
--
-- KEYS[1] queue zset
-- KEYS[2] admitted zset
-- ARGV[1] token
-- ARGV[2] now, epoch seconds
--
-- Returns { status, position, queueLength, admissionExpiresAt }, all as strings.

local queueKey, admittedKey = KEYS[1], KEYS[2]
local token = ARGV[1]
local now = tonumber(ARGV[2])

local queueLength = tostring(redis.call('ZCARD', queueKey))

local admittedUntil = redis.call('ZSCORE', admittedKey, token)
if admittedUntil then
    -- A lapsed pass is reported rather than deleted: reclaiming it is admit.lua's job,
    -- which keeps this path free of writes.
    local status = 'EXPIRED'
    if tonumber(admittedUntil) > now then
        status = 'ADMITTED'
    end
    return { status, '0', queueLength, admittedUntil }
end

local rank = redis.call('ZRANK', queueKey, token)
if not rank then
    return { 'NOT_QUEUED', '0', queueLength, '0' }
end
return { 'WAITING', tostring(rank + 1), queueLength, '0' }
