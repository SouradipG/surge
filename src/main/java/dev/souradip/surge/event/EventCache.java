package dev.souradip.surge.event;

import dev.souradip.surge.RedisKeys;
import dev.souradip.surge.SurgeProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two levels of cache in front of the events table, built for the moment a sale opens and a
 * hundred thousand people load the same page in the same second.
 *
 * <ul>
 *   <li><b>Local, 250 ms.</b> Even Redis has a limit for one hot key. A very short in process
 *       cache absorbs most reads before they leave the instance.</li>
 *   <li><b>Redis, about 2 s with jitter.</b> Shared by every instance. The jitter stops every
 *       instance's copy from expiring on the same tick.</li>
 *   <li><b>Single flight.</b> When the entry is missing, only one request per instance goes to
 *       Postgres. The others wait for that load instead of all running the same query, which
 *       is what turns a cache miss into a database outage (the thundering herd).</li>
 * </ul>
 *
 * <p>Ticket counts are never cached here. They change on every hold and are read live from the
 * Redis counter.
 */
@Component
public class EventCache {

    private static final Logger log = LoggerFactory.getLogger(EventCache.class);

    private record Local(EventDetails details, long expiresAtNanos) {}

    private final EventRepository events;
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final SurgeProperties.Cache config;
    private final Map<Long, Local> local = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Optional<EventDetails>>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong databaseLoads = new AtomicLong();

    EventCache(EventRepository events, StringRedisTemplate redis, JsonMapper json, SurgeProperties properties) {
        this.events = events;
        this.redis = redis;
        this.json = json;
        this.config = properties.cache();
    }

    public Optional<EventDetails> get(long eventId) {
        Local hit = local.get(eventId);
        if (hit != null && hit.expiresAtNanos() > System.nanoTime()) {
            return Optional.of(hit.details());
        }

        CompletableFuture<Optional<EventDetails>> mine = new CompletableFuture<>();
        CompletableFuture<Optional<EventDetails>> leader = inFlight.putIfAbsent(eventId, mine);
        if (leader != null) {
            return leader.join();
        }
        try {
            Optional<EventDetails> loaded = load(eventId);
            loaded.ifPresent(d -> local.put(eventId, new Local(d, System.nanoTime() + config.localTtl().toNanos())));
            mine.complete(loaded);
            return loaded;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(eventId, mine);
        }
    }

    public void evict(long eventId) {
        local.remove(eventId);
        redis.delete(RedisKeys.eventCache(eventId));
    }

    long databaseLoads() {
        return databaseLoads.get();
    }

    private Optional<EventDetails> load(long eventId) {
        String key = RedisKeys.eventCache(eventId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return Optional.of(json.readValue(cached, EventDetails.class));
            }
        } catch (DataAccessException e) {
            // Redis is a cache here, not a dependency. Fall through to Postgres.
            log.warn("event cache read failed, going to the database: {}", e.toString());
        }

        databaseLoads.incrementAndGet();
        Optional<EventDetails> fromDb = events.find(eventId);
        fromDb.ifPresent(details -> {
            try {
                redis.opsForValue().set(key, json.writeValueAsString(details), jittered(config.ttl()));
            } catch (DataAccessException e) {
                log.warn("event cache write failed: {}", e.toString());
            }
        });
        return fromDb;
    }

    private static Duration jittered(Duration ttl) {
        long millis = ttl.toMillis();
        return Duration.ofMillis(millis + ThreadLocalRandom.current().nextLong(Math.max(1, millis / 5)));
    }
}
