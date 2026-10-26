-- Token bucket, evaluated atomically inside Redis.
-- KEYS[1]  bucket hash (fields: tokens, ts)
-- ARGV[1]  capacity, ARGV[2] refill per second, ARGV[3] cost
-- Returns { allowed (1/0), tokens left (string), retry after in ms }
--
-- Time comes from Redis TIME, not from the caller. Several app instances with
-- slightly different clocks would otherwise refill the same bucket at
-- different rates.
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1]) or capacity
local ts = tonumber(state[2]) or now

tokens = math.min(capacity, tokens + math.max(0, now - ts) * refill / 1000)

local allowed = 0
local retry_after = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry_after = math.ceil((cost - tokens) * 1000 / refill)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
-- An idle bucket refills to full anyway, so let Redis drop it.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / refill * 1000) + 1000)

return { allowed, tostring(tokens), retry_after }
