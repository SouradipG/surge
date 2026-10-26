package dev.souradip.surge.ratelimit;

import dev.souradip.surge.RedisKeys;
import dev.souradip.surge.SurgeProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Token bucket shared by every instance, stored in Redis.
 *
 * <p>A limiter kept in each instance's memory allows N times the limit once N instances sit
 * behind a load balancer, and resets on every deploy. The bucket lives in Redis and the
 * check and decrement run as one Lua script, so two concurrent requests cannot both spend the
 * last token.
 *
 * <p>Token bucket rather than a sliding window log: it allows short bursts (a user clicking
 * twice), costs one small hash per subject, and needs no per request entries.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, double tokensLeft, long retryAfterMillis) {}

    private final StringRedisTemplate redis;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> script;
    private final SurgeProperties.RateLimit config;

    @SuppressWarnings("rawtypes")
    RateLimiter(StringRedisTemplate redis, RedisScript<List> tokenBucketScript, SurgeProperties properties) {
        this.redis = redis;
        this.script = tokenBucketScript;
        this.config = properties.rateLimit();
    }

    public Decision tryAcquire(String subject) {
        return tryAcquire(subject, config.capacity(), config.refillPerSecond());
    }

    public Decision tryAcquire(String subject, int capacity, int refillPerSecond) {
        List<?> result = redis.execute(script, List.of(RedisKeys.bucket(subject)),
                String.valueOf(capacity), String.valueOf(refillPerSecond), "1");
        return new Decision(
                ((Number) result.get(0)).longValue() == 1,
                Double.parseDouble(result.get(1).toString()),
                ((Number) result.get(2)).longValue());
    }
}
