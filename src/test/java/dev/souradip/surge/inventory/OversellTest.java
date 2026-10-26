package dev.souradip.surge.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.souradip.surge.Concurrently;
import dev.souradip.surge.IntegrationTestBase;
import dev.souradip.surge.api.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole point of a flash sale system: 2,000 people, 100 tickets, the same instant. A read,
 * check, write on a shared count sells more than 100. Exactly 100 may succeed.
 */
class OversellTest extends IntegrationTestBase {

    @Autowired
    HoldService holds;

    @Test
    void neverHoldsMoreThanCapacity() throws Exception {
        long eventId = eventOnSale(100);

        List<Concurrently.Outcome<HoldView>> outcomes = Concurrently.run(2000, i -> {
            String user = "user-" + i;
            return holds.hold(eventId, user, 1, admissionFor(eventId, user));
        });

        long held = outcomes.stream().filter(o -> !o.failed()).count();
        long soldOut = outcomes.stream()
                .filter(o -> o.error() instanceof ApiException e && e.code().equals("SOLD_OUT"))
                .count();

        assertThat(held).isEqualTo(100);
        assertThat(soldOut).as("everyone else is told it is sold out, nobody gets an error").isEqualTo(1900);
        assertThat(inventory.available(eventId)).isZero();
        assertThat(holdsIn(eventId, "HELD")).isEqualTo(100);
    }

    @Test
    void everyHolderCanPayAndSoldStopsAtCapacity() throws Exception {
        long eventId = eventOnSale(50);
        List<Concurrently.Outcome<HoldView>> outcomes = Concurrently.run(300, i -> {
            String user = "user-" + i;
            return holds.hold(eventId, user, 1, admissionFor(eventId, user));
        });
        List<HoldView> won = outcomes.stream().filter(o -> !o.failed()).map(Concurrently.Outcome::value).toList();

        List<Concurrently.Outcome<OrderView>> paid = Concurrently.run(won.size(), i ->
                holds.purchase(won.get(i).id(), won.get(i).userId()));

        assertThat(paid).noneMatch(Concurrently.Outcome::failed);
        assertThat(sold(eventId)).isEqualTo(50);
    }

    @Test
    void perUserLimitHoldsUnderConcurrency() throws Exception {
        long eventId = eventOnSale(1000);
        String token = admissionFor(eventId, "scalper");

        List<Concurrently.Outcome<HoldView>> outcomes = Concurrently.run(50, i -> holds.hold(eventId, "scalper", 1, token));

        long held = outcomes.stream().filter(o -> !o.failed()).count();
        assertThat(held).as("max-per-user is 4, however many requests arrive at once").isEqualTo(4);
        assertThat(outcomes)
                .filteredOn(Concurrently.Outcome::failed)
                .allMatch(o -> o.error() instanceof ApiException e && e.code().equals("LIMIT_REACHED"));
        assertThat(inventory.available(eventId)).isEqualTo(996);
    }

    @Test
    void aRequestThatDoesNotFitTakesNothing() {
        long eventId = eventOnSale(3);
        holds.hold(eventId, "a", 2, admissionFor(eventId, "a"));

        assertThat(catchThrowable(() -> holds.hold(eventId, "b", 2, admissionFor(eventId, "b"))))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SOLD_OUT"));
        assertThat(inventory.available(eventId)).as("a refused request takes nothing").isEqualTo(1);

        holds.hold(eventId, "c", 1, admissionFor(eventId, "c"));
        assertThat(inventory.available(eventId)).isZero();
    }
}
