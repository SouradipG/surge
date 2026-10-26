-- Reserve units for a hold.
-- KEYS[1] available counter, KEYS[2] per user hash, KEYS[3] ops counter
-- ARGV[1] quantity, ARGV[2] user id, ARGV[3] max per user
-- Returns remaining units, or -1 sold out, -2 not initialised, -3 per user limit.
--
-- All three keys share the {event:N} hash tag, so they live on the same slot and
-- this works on Redis Cluster. Without the tag a multi key script fails there
-- with CROSSSLOT, even though it works on a single local Redis.
local qty = tonumber(ARGV[1])
local available = redis.call('GET', KEYS[1])
if not available then return -2 end
available = tonumber(available)

local mine = tonumber(redis.call('HGET', KEYS[2], ARGV[2]) or '0')
if mine + qty > tonumber(ARGV[3]) then return -3 end
if available < qty then return -1 end

redis.call('DECRBY', KEYS[1], qty)
redis.call('HINCRBY', KEYS[2], ARGV[2], qty)
redis.call('INCR', KEYS[3])
return available - qty
