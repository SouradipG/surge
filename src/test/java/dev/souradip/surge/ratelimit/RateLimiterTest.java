package dev.souradip.surge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.souradip.surge.Concurrently;
import dev.souradip.surge.IntegrationTestBase;
import dev.souradip.surge.SurgeProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.RedisScript;

class RateLimiterTest extends IntegrationTestBase {

    @Autowired
    RateLimiter limiter;

    @Autowired
    @SuppressWarnings("rawtypes")
    RedisScript<List> tokenBucketScript;

    @Autowired
    SurgeProperties properties;

    @Test
    void allowsABurstUpToCapacityThenSaysWhenToComeBack() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("burst", 5, 1).allowed()).isTrue();
        }
        RateLimiter.Decision refused = limiter.tryAcquire("burst", 5, 1);

        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterMillis()).isBetween(1L, 1000L);
    }

    @Test
    void refillsOverTime() throws Exception {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("refill", 3, 10);
        }
        assertThat(limiter.tryAcquire("refill", 3, 10).allowed()).isFalse();

        Thread.sleep(250);

        assertThat(limiter.tryAcquire("refill", 3, 10).allowed()).as("10 per second refills two tokens in 250 ms").isTrue();
    }

    @Test
    void concurrentRequestsCannotSpendTheSameToken() throws Exception {
        List<Concurrently.Outcome<RateLimiter.Decision>> outcomes =
                Concurrently.run(500, i -> limiter.tryAcquire("race", 50, 1));

        long allowed = outcomes.stream().filter(o -> !o.failed() && o.value().allowed()).count();
        // 50 from the bucket, plus at most the one or two that refill while the test runs.
        assertThat(allowed).isBetween(50L, 52L);
    }

    /**
     * Three instances behind a load balancer. A bucket kept in each instance's memory gives a
     * client three times the limit. The Redis bucket gives the limit, whichever instance answers.
     */
    @Test
    void limitHoldsAcrossInstances() throws Exception {
        List<RateLimiter> instances = List.of(
                limiter,
                new RateLimiter(redis, tokenBucketScript, properties),
                new RateLimiter(redis, tokenBucketScript, properties));
        List<LocalBucket> naive = List.of(new LocalBucket(30), new LocalBucket(30), new LocalBucket(30));

        List<Concurrently.Outcome<Boolean>> shared = Concurrently.run(300, i ->
                instances.get(i % 3).tryAcquire("client-1", 30, 1).allowed());
        List<Concurrently.Outcome<Boolean>> local = Concurrently.run(300, i -> naive.get(i % 3).tryAcquire());

        assertThat(shared.stream().filter(o -> Boolean.TRUE.equals(o.value())).count()).isBetween(30L, 32L);
        assertThat(local.stream().filter(o -> Boolean.TRUE.equals(o.value())).count())
                .as("the in memory version lets three times as much through")
                .isEqualTo(90);
    }

    /** What most people write first. Correct on one instance, wrong on three. */
    private static final class LocalBucket {

        private final AtomicInteger tokens;

        LocalBucket(int capacity) {
            this.tokens = new AtomicInteger(capacity);
        }

        boolean tryAcquire() {
            return tokens.getAndUpdate(t -> t > 0 ? t - 1 : t) > 0;
        }
    }
}
