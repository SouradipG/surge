-- Give units back after a hold expires or a reservation is rolled back.
-- KEYS[1] available counter, KEYS[2] per user hash, KEYS[3] ops counter
-- ARGV[1] quantity, ARGV[2] user id
local qty = tonumber(ARGV[1])
redis.call('INCRBY', KEYS[1], qty)
local left = redis.call('HINCRBY', KEYS[2], ARGV[2], -qty)
if left <= 0 then redis.call('HDEL', KEYS[2], ARGV[2]) end
redis.call('INCR', KEYS[3])
return 1
