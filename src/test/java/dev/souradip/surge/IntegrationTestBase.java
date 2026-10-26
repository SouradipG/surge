package dev.souradip.surge;

import dev.souradip.surge.event.EventDetails;
import dev.souradip.surge.event.EventRepository;
import dev.souradip.surge.inventory.InventoryStore;
import dev.souradip.surge.waitingroom.AdmissionTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every test runs against real Postgres and real Redis. The interesting failures here (two
 * requests taking the last ticket, a script touching keys on different slots, a lock that two
 * instances both think they hold) only exist with the real thing.
 *
 * <p>Limits are raised so tests that are not about limiting are not limited, and the background
 * jobs are slowed to once an hour so each test drives them by hand and stays deterministic.
 */
@SpringBootTest(properties = {
        "surge.rate-limit.capacity=1000000",
        "surge.rate-limit.refill-per-second=1000000",
        "surge.shedding.max-in-flight=100000",
        "surge.waiting-room.tick=1h",
        "surge.holds.expiry-poll=1h",
        "surge.reconcile.interval=1h",
        "spring.datasource.hikari.connection-timeout=30000"
})
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected EventRepository events;

    @Autowired
    protected InventoryStore inventory;

    @Autowired
    protected AdmissionTokens tokens;

    @BeforeEach
    void cleanState() {
        jdbc.sql("truncate orders, holds, inventory, events restart identity cascade").update();
        flushRedis();
    }

    protected void flushRedis() {
        redis.execute((RedisCallback<Object>) connection -> {
            flushAll(connection);
            return null;
        });
    }

    private static void flushAll(RedisConnection connection) {
        connection.serverCommands().flushAll();
    }

    /** Creates an event and puts it on sale, the same way the API does. */
    protected long eventOnSale(int capacity) {
        EventDetails event = events.create("Test event", capacity);
        inventory.initialise(event.id(), capacity);
        return event.id();
    }

    protected String admissionFor(long eventId, String userId) {
        return tokens.issue(eventId, userId);
    }

    protected int sold(long eventId) {
        return jdbc.sql("select sold from inventory where event_id = :id").param("id", eventId).query(Integer.class).single();
    }

    protected int holdsIn(long eventId, String status) {
        return jdbc.sql("select coalesce(sum(quantity), 0)::int from holds where event_id = :id and status = :status")
                .param("id", eventId).param("status", status).query(Integer.class).single();
    }
}
