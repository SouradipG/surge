package dev.souradip.surge.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.souradip.surge.Concurrently;
import dev.souradip.surge.IntegrationTestBase;
import dev.souradip.surge.api.ApiException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HoldLifecycleTest extends IntegrationTestBase {

    @Autowired
    HoldService holds;

    @Test
    void expiredHoldReturnsTicketsAndCannotBePaid() {
        long eventId = eventOnSale(10);
        HoldView hold = holds.hold(eventId, "u1", 3, admissionFor(eventId, "u1"));
        assertThat(inventory.available(eventId)).isEqualTo(7);

        expireNow(hold.id());
        assertThat(holds.expireDue()).isEqualTo(1);

        assertThat(inventory.available(eventId)).isEqualTo(10);
        assertThat(catchThrowable(() -> holds.purchase(hold.id(), "u1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("HOLD_EXPIRED"));
        assertThat(sold(eventId)).isZero();
    }

    @Test
    void holdPastItsExpiryCannotBePaidEvenBeforeTheWorkerRuns() {
        long eventId = eventOnSale(10);
        HoldView hold = holds.hold(eventId, "u1", 1, admissionFor(eventId, "u1"));
        expireNow(hold.id());

        assertThat(catchThrowable(() -> holds.purchase(hold.id(), "u1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("HOLD_EXPIRED"));
    }

    @Test
    void retriedPurchaseReturnsTheSameOrder() throws Exception {
        long eventId = eventOnSale(10);
        HoldView hold = holds.hold(eventId, "u1", 2, admissionFor(eventId, "u1"));

        List<Concurrently.Outcome<OrderView>> outcomes = Concurrently.run(20, i -> holds.purchase(hold.id(), "u1"));

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        assertThat(outcomes.stream().map(o -> o.value().id()).distinct()).hasSize(1);
        assertThat(sold(eventId)).as("a double click must not sell twice").isEqualTo(2);
    }

    @Test
    void someoneElsesHoldLooksLikeNoHold() {
        long eventId = eventOnSale(10);
        HoldView hold = holds.hold(eventId, "owner", 1, admissionFor(eventId, "owner"));

        assertThat(catchThrowable(() -> holds.purchase(hold.id(), "intruder")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("HOLD_NOT_FOUND"));
        assertThat(catchThrowable(() -> holds.purchase(UUID.randomUUID(), "owner")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("HOLD_NOT_FOUND"));
    }

    /**
     * Payment arrives in the same moment the hold expires. If both sides win, the buyer pays and
     * their ticket is also handed back to the pool and sold to someone else. For every hold
     * exactly one side must win, and Redis must agree with Postgres afterwards.
     */
    @Test
    void purchaseRacingExpiryHasExactlyOneWinnerPerHold() throws Exception {
        long eventId = eventOnSale(200);
        List<HoldView> held = Concurrently.run(200, i -> {
            String user = "user-" + i;
            return holds.hold(eventId, user, 1, admissionFor(eventId, user));
        }).stream().map(Concurrently.Outcome::value).toList();

        jdbc.sql("update holds set expires_at = now() + interval '150 milliseconds'").update();

        // 200 threads pay at staggered moments around the deadline, 20 keep running the expiry job across it.
        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(220, i -> {
            if (i < 200) {
                sleepQuietly(100 + (i % 100));
                try {
                    holds.purchase(held.get(i).id(), held.get(i).userId());
                    return 1;
                } catch (ApiException e) {
                    return 0;
                }
            }
            int expired = 0;
            long until = System.currentTimeMillis() + 400;
            while (System.currentTimeMillis() < until) {
                expired += holds.expireDue();
            }
            return expired;
        });
        holds.expireDue();

        assertThat(outcomes).noneMatch(Concurrently.Outcome::failed);
        int purchased = outcomes.subList(0, 200).stream().mapToInt(Concurrently.Outcome::value).sum();
        int expired = outcomes.subList(200, 220).stream().mapToInt(Concurrently.Outcome::value).sum();

        int orders = jdbc.sql("select count(*)::int from orders where event_id = :id").param("id", eventId).query(Integer.class).single();
        assertThat(orders).isEqualTo(purchased);
        assertThat(sold(eventId)).isEqualTo(purchased);
        assertThat(holdsIn(eventId, "PURCHASED") + holdsIn(eventId, "EXPIRED")).isEqualTo(200);
        assertThat(holdsIn(eventId, "EXPIRED")).isGreaterThanOrEqualTo(expired);
        assertThat(inventory.available(eventId))
                .as("every expired hold was handed back exactly once, no purchased one was")
                .isEqualTo(200 - purchased);
    }

    private void expireNow(UUID holdId) {
        jdbc.sql("update holds set expires_at = now() - interval '1 second' where id = :id").param("id", holdId).update();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
