package dev.souradip.surge.shedding;

import static org.assertj.core.api.Assertions.assertThat;

import dev.souradip.surge.SurgeProperties;
import org.junit.jupiter.api.Test;

class LoadShedderTest {

    private final LoadShedder shedder = new LoadShedder(
            new SurgeProperties(null, null, null, null, new SurgeProperties.Shedding(10, 0.6), null));

    @Test
    void browsingIsShedFirstSoCheckoutKeepsCapacity() {
        int browsing = 0;
        while (shedder.tryEnter(LoadShedder.Priority.BROWSE)) {
            browsing++;
        }
        assertThat(browsing).isEqualTo(6);

        int checkingOut = 0;
        while (shedder.tryEnter(LoadShedder.Priority.CHECKOUT)) {
            checkingOut++;
        }
        assertThat(checkingOut).as("the last 40 percent is kept for people paying").isEqualTo(4);
        assertThat(shedder.inFlight()).isEqualTo(10);
    }

    @Test
    void capacityComesBackAsRequestsFinish() {
        for (int i = 0; i < 10; i++) {
            shedder.tryEnter(LoadShedder.Priority.CHECKOUT);
        }
        assertThat(shedder.tryEnter(LoadShedder.Priority.CHECKOUT)).isFalse();

        shedder.exit();

        assertThat(shedder.tryEnter(LoadShedder.Priority.CHECKOUT)).isTrue();
    }
}
