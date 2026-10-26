package dev.souradip.surge.inventory;

import dev.souradip.surge.RedisKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The fast path for inventory: one counter per event in Redis.
 *
 * <p>Thousands of buyers decrementing a single Postgres row would all queue on that row's lock,
 * and the whole sale would run at the speed of one row. Redis does the contended part
 * atomically in memory. Postgres stays the source of truth, and {@link InventoryReconciler}
 * rebuilds this counter from it when the two disagree.
 */
@Component
public class InventoryStore {

    public enum ReserveResult { RESERVED, SOLD_OUT, LIMIT_REACHED, NOT_INITIALISED }

    private final StringRedisTemplate redis;
    private final RedisScript<Long> reserve;
    private final RedisScript<Long> release;
    private final RedisScript<Long> reconcile;

    InventoryStore(StringRedisTemplate redis, RedisScript<Long> reserveScript, RedisScript<Long> releaseScript,
                   RedisScript<Long> reconcileScript) {
        this.redis = redis;
        this.reserve = reserveScript;
        this.release = releaseScript;
        this.reconcile = reconcileScript;
    }

    public void initialise(long eventId, int capacity) {
        redis.opsForValue().setIfAbsent(RedisKeys.available(eventId), String.valueOf(capacity));
        redis.opsForSet().add(RedisKeys.ACTIVE_EVENTS, String.valueOf(eventId));
    }

    public ReserveResult reserve(long eventId, String userId, int quantity, int maxPerUser) {
        Long result = redis.execute(reserve, keys(eventId), String.valueOf(quantity), userId, String.valueOf(maxPerUser));
        return switch (result.intValue()) {
            case -1 -> ReserveResult.SOLD_OUT;
            case -2 -> ReserveResult.NOT_INITIALISED;
            case -3 -> ReserveResult.LIMIT_REACHED;
            default -> ReserveResult.RESERVED;
        };
    }

    public void release(long eventId, String userId, int quantity) {
        redis.execute(release, keys(eventId), String.valueOf(quantity), userId);
    }

    public long available(long eventId) {
        String value = redis.opsForValue().get(RedisKeys.available(eventId));
        return value == null ? -1 : Long.parseLong(value);
    }

    public String opsVersion(long eventId) {
        String value = redis.opsForValue().get(RedisKeys.ops(eventId));
        return value == null ? "0" : value;
    }

    /** @return true if applied, false if a reserve or release ran since {@code expectedOps} was read */
    public boolean overwrite(long eventId, String expectedOps, long available, Map<String, Integer> perUser) {
        List<String> args = new ArrayList<>();
        args.add(expectedOps);
        args.add(String.valueOf(available));
        perUser.forEach((user, qty) -> {
            args.add(user);
            args.add(String.valueOf(qty));
        });
        return redis.execute(reconcile, keys(eventId), args.toArray()) == 1L;
    }

    private static List<String> keys(long eventId) {
        return List.of(RedisKeys.available(eventId), RedisKeys.perUser(eventId), RedisKeys.ops(eventId));
    }
}
