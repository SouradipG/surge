package dev.souradip.surge.waitingroom;

import dev.souradip.surge.RedisKeys;
import dev.souradip.surge.SurgeProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A FIFO queue in a Redis sorted set, drained at the rate the backend can actually serve.
 *
 * <p>This is admission control. Instead of letting a million people hit checkout at once and
 * failing most of them slowly, everyone gets a place in line and is let in in batches.
 */
@Component
public class WaitingRoom {

    public sealed interface Status {
        record Waiting(long position) implements Status {}

        record Admitted(String token) implements Status {}

        record NotQueued() implements Status {}
    }

    private final StringRedisTemplate redis;
    private final RedisScript<Long> joinScript;
    private final AdmissionTokens tokens;
    private final SurgeProperties.WaitingRoom config;
    private final String instanceId = UUID.randomUUID().toString();

    WaitingRoom(StringRedisTemplate redis, RedisScript<Long> joinQueueScript, AdmissionTokens tokens, SurgeProperties properties) {
        this.redis = redis;
        this.joinScript = joinQueueScript;
        this.tokens = tokens;
        this.config = properties.waitingRoom();
    }

    public Status join(long eventId, String userId) {
        Status current = status(eventId, userId);
        if (current instanceof Status.Admitted) {
            return current;
        }
        Long position = redis.execute(joinScript, List.of(RedisKeys.queue(eventId), RedisKeys.queueSeq(eventId)), userId);
        return new Status.Waiting(position);
    }

    public Status status(long eventId, String userId) {
        String token = redis.opsForValue().get(RedisKeys.admitted(eventId, userId));
        if (token != null) {
            return new Status.Admitted(token);
        }
        Long rank = redis.opsForZSet().rank(RedisKeys.queue(eventId), userId);
        return rank == null ? new Status.NotQueued() : new Status.Waiting(rank);
    }

    /**
     * Lets the next batch in. Every instance runs the ticker, but only the one holding the
     * per event lock for this tick admits anyone. Without the lock, five instances would each
     * admit a batch and the backend would see five times the rate it was sized for.
     *
     * @return how many users were admitted
     */
    public int admitNextBatch(long eventId) {
        Boolean locked = redis.opsForValue().setIfAbsent(RedisKeys.admissionLock(eventId), instanceId, config.tick());
        if (!Boolean.TRUE.equals(locked)) {
            return 0;
        }
        return admit(eventId, config.admitPerTick());
    }

    int admit(long eventId, int count) {
        Set<ZSetOperations.TypedTuple<String>> batch = redis.opsForZSet().popMin(RedisKeys.queue(eventId), count);
        if (batch == null) {
            return 0;
        }
        Duration ttl = config.tokenTtl();
        for (ZSetOperations.TypedTuple<String> entry : batch) {
            String userId = entry.getValue();
            redis.opsForValue().set(RedisKeys.admitted(eventId, userId), tokens.issue(eventId, userId), ttl);
        }
        return batch.size();
    }
}
