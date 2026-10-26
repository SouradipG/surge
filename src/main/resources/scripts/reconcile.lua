-- Overwrite the Redis view of an event with numbers computed from Postgres,
-- but only if no reserve or release ran in between. Otherwise the numbers are
-- already stale and the caller tries again on its next pass.
-- KEYS[1] available counter, KEYS[2] per user hash, KEYS[3] ops counter
-- ARGV[1] expected ops value, ARGV[2] available, ARGV[3..] user, qty pairs
local ops = redis.call('GET', KEYS[3]) or '0'
if ops ~= ARGV[1] then return 0 end

redis.call('SET', KEYS[1], ARGV[2])
redis.call('DEL', KEYS[2])
for i = 3, #ARGV, 2 do
  redis.call('HSET', KEYS[2], ARGV[i], ARGV[i + 1])
end
redis.call('INCR', KEYS[3])
return 1
