package dev.souradip.surge.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.souradip.surge.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
@TestPropertySource(properties = {"surge.rate-limit.capacity=5", "surge.rate-limit.refill-per-second=1"})
class RateLimitHttpTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void burstPastCapacityGets429WithRetryAfter() throws Exception {
        long eventId = eventOnSale(10);
        List<MvcResult> results = new ArrayList<>();
        // Every request has a fresh user, so it is the per IP bucket that runs out.
        for (int i = 0; i < 10; i++) {
            results.add(mvc.perform(get("/events/{id}", eventId).header("X-User-Id", "u" + i)).andReturn());
        }

        assertThat(results.subList(0, 5)).allMatch(r -> r.getResponse().getStatus() == 200);
        MvcResult limited = results.stream().filter(r -> r.getResponse().getStatus() == 429).findFirst().orElseThrow();
        assertThat(limited.getResponse().getHeader("Retry-After")).isEqualTo("1");
        assertThat(limited.getResponse().getContentAsString()).contains("RATE_LIMITED");
    }

    @Test
    void healthChecksAreNeverLimited() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }
}
