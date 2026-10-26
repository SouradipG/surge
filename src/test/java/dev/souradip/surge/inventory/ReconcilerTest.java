package dev.souradip.surge.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.souradip.surge.IntegrationTestBase;
import dev.souradip.surge.RedisKeys;
import dev.souradip.surge.api.ApiException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Redis is fast but it is not where the truth lives. These tests break it on purpose and check
 * that the reconciler rebuilds it from Postgres without losing a sale or a per user limit.
 */
class ReconcilerTest extends IntegrationTestBase {

    @Autowired
    HoldService holds;

    @Autowired
    InventoryReconciler reconciler;

    @Test
    void rebuildsCountersAfterRedisLosesEverything() {
        long eventId = eventOnSale(100);
        HoldView paid = holds.hold(eventId, "a", 4, admissionFor(eventId, "a"));
        holds.purchase(paid.id(), "a");
        holds.hold(eventId, "b", 3, admissionFor(eventId, "b"));
        HoldView lapsed = holds.hold(eventId, "c", 2, admissionFor(eventId, "c"));
        jdbc.sql("update holds set expires_at = now() - interval '1 second' where id = :id").param("id", lapsed.id()).update();
        holds.expireDue();

        flushRedis();
        assertThat(catchThrowable(() -> holds.hold(eventId, "d", 1, admissionFor(eventId, "d"))))
                .as("with the counter gone the sale pauses instead of guessing")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("EVENT_NOT_ON_SALE"));

        assertThat(reconciler.reconcile(eventId)).isTrue();

        assertThat(inventory.available(eventId)).isEqualTo(100 - 4 - 3);
        assertThat(catchThrowable(() -> holds.hold(eventId, "a", 1, admissionFor(eventId, "a"))))
                .as("a's four tickets still count toward the limit after the rebuild")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("LIMIT_REACHED"));
        holds.hold(eventId, "c", 4, admissionFor(eventId, "c"));
    }

    @Test
    void correctsACounterThatDrifted() {
        long eventId = eventOnSale(20);
        holds.hold(eventId, "a", 2, admissionFor(eventId, "a"));
        // A hold whose Postgres insert failed after Redis took the units, and whose
        // compensating release never ran because the process died.
        redis.opsForValue().decrement(RedisKeys.available(eventId), 5);
        assertThat(inventory.available(eventId)).isEqualTo(13);

        reconciler.reconcile(eventId);

        assertThat(inventory.available(eventId)).isEqualTo(18);
    }

    @Test
    void refusesToOverwriteWhenAReserveLandedInBetween() {
        long eventId = eventOnSale(20);
        String seen = inventory.opsVersion(eventId);

        holds.hold(eventId, "a", 1, admissionFor(eventId, "a"));

        assertThat(inventory.overwrite(eventId, seen, 20, Map.of()))
                .as("numbers computed before the reserve are stale and must not win")
                .isFalse();
        assertThat(inventory.available(eventId)).isEqualTo(19);
    }
}
