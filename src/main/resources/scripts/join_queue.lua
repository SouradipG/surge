-- Join the waiting room once. Rejoining keeps your original place.
-- KEYS[1] queue sorted set, KEYS[2] sequence counter
-- ARGV[1] user id
-- Returns the 0 based position.
if not redis.call('ZSCORE', KEYS[1], ARGV[1]) then
  local seq = redis.call('INCR', KEYS[2])
  redis.call('ZADD', KEYS[1], seq, ARGV[1])
end
return redis.call('ZRANK', KEYS[1], ARGV[1])
