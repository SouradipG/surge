package dev.souradip.surge.waitingroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.souradip.surge.Concurrently;
import dev.souradip.surge.IntegrationTestBase;
import dev.souradip.surge.RedisKeys;
import dev.souradip.surge.SurgeProperties;
import dev.souradip.surge.api.ApiException;
import dev.souradip.surge.inventory.HoldService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.RedisScript;

class WaitingRoomTest extends IntegrationTestBase {

    @Autowired
    WaitingRoom room;

    @Autowired
    HoldService holds;

    @Autowired
    SurgeProperties properties;

    @Autowired
    RedisScript<Long> joinQueueScript;

    @Test
    void firstInFirstOutAndRejoiningKeepsYourPlace() {
        long eventId = eventOnSale(100);
        for (int i = 0; i < 10; i++) {
            assertThat(room.join(eventId, "u" + i)).isEqualTo(new WaitingRoom.Status.Waiting(i));
        }
        assertThat(room.join(eventId, "u3")).as("refreshing the page does not send you to the back").isEqualTo(new WaitingRoom.Status.Waiting(3));

        assertThat(room.admit(eventId, 3)).isEqualTo(3);

        assertThat(room.status(eventId, "u0")).isInstanceOf(WaitingRoom.Status.Admitted.class);
        assertThat(room.status(eventId, "u2")).isInstanceOf(WaitingRoom.Status.Admitted.class);
        assertThat(room.status(eventId, "u3")).isEqualTo(new WaitingRoom.Status.Waiting(0));
        assertThat(room.status(eventId, "stranger")).isEqualTo(new WaitingRoom.Status.NotQueued());
    }

    @Test
    void concurrentJoinsGetDistinctPlaces() throws Exception {
        long eventId = eventOnSale(100);

        List<Concurrently.Outcome<WaitingRoom.Status>> outcomes = Concurrently.run(1000, i -> room.join(eventId, "u" + i));

        assertThat(outcomes.stream().map(o -> ((WaitingRoom.Status.Waiting) o.value()).position()).distinct()).hasSize(1000);
        assertThat(redis.opsForZSet().size(RedisKeys.queue(eventId))).isEqualTo(1000);
    }

    /**
     * Five instances run the ticker at the same moment. Only one may admit, or the backend sees
     * five batches per tick instead of the one it was sized for.
     */
    @Test
    void onlyOneInstanceAdmitsPerTick() throws Exception {
        long eventId = eventOnSale(1000);
        for (int i = 0; i < 500; i++) {
            room.join(eventId, "u" + i);
        }
        List<WaitingRoom> instances = List.of(room,
                otherInstance(), otherInstance(), otherInstance(), otherInstance());

        List<Concurrently.Outcome<Integer>> outcomes = Concurrently.run(5, i -> instances.get(i).admitNextBatch(eventId));

        int admitted = outcomes.stream().mapToInt(Concurrently.Outcome::value).sum();
        assertThat(admitted).isEqualTo(properties.waitingRoom().admitPerTick());
        assertThat(redis.opsForZSet().size(RedisKeys.queue(eventId))).isEqualTo(500 - admitted);
    }

    @Test
    void admittedUserCanHold() {
        long eventId = eventOnSale(10);
        room.join(eventId, "fan");
        room.admit(eventId, 1);
        String token = ((WaitingRoom.Status.Admitted) room.status(eventId, "fan")).token();

        assertThat(holds.hold(eventId, "fan", 2, token).quantity()).isEqualTo(2);
    }

    @Test
    void holdsWithoutAValidTokenAreRefused() {
        long eventId = eventOnSale(10);
        long otherEvent = eventOnSale(10);
        String fansToken = admissionFor(eventId, "fan");

        assertNotAdmitted(() -> holds.hold(eventId, "bot", 1, null));
        assertNotAdmitted(() -> holds.hold(eventId, "bot", 1, fansToken));
        assertNotAdmitted(() -> holds.hold(otherEvent, "fan", 1, fansToken));
        assertNotAdmitted(() -> holds.hold(eventId, "fan", 1, fansToken.substring(0, fansToken.length() - 2) + "xx"));
        assertNotAdmitted(() -> holds.hold(eventId, "fan", 1, "not-a-token"));
        assertThat(inventory.available(eventId)).as("refused before any ticket was touched").isEqualTo(10);
    }

    @Test
    void expiredTokenIsRefused() {
        long eventId = eventOnSale(10);
        Clock lastHour = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String stale = new AdmissionTokens(properties, lastHour).issue(eventId, "fan");

        assertThat(catchThrowable(() -> tokens.verify(stale, eventId, "fan")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("expired"));
    }

    private WaitingRoom otherInstance() {
        return new WaitingRoom(redis, joinQueueScript, tokens, properties);
    }

    private static void assertNotAdmitted(Runnable call) {
        assertThat(catchThrowable(call::run))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_ADMITTED"));
    }
}
