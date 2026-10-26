package dev.souradip.surge.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.souradip.surge.Concurrently;
import dev.souradip.surge.IntegrationTestBase;
import dev.souradip.surge.SurgeProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@AutoConfigureMockMvc
class EventCacheTest extends IntegrationTestBase {

    @Autowired
    EventCache cache;

    @Autowired
    MockMvc mvc;

    /**
     * The sale opens, the cache entry is cold, and a thousand requests arrive for the same page.
     * Without single flight that is a thousand identical queries against Postgres at once.
     */
    @Test
    void coldCacheUnderABurstHitsTheDatabaseOnce() throws Exception {
        long eventId = eventOnSale(100);
        cache.evict(eventId);
        long before = cache.databaseLoads();

        List<Concurrently.Outcome<Optional<EventDetails>>> outcomes = Concurrently.run(1000, i -> cache.get(eventId));

        assertThat(outcomes).allMatch(o -> !o.failed() && o.value().isPresent());
        assertThat(cache.databaseLoads() - before).isEqualTo(1);
    }

    @Test
    void secondInstanceReadsFromRedisNotPostgres() {
        long eventId = eventOnSale(100);
        cache.evict(eventId);
        cache.get(eventId);
        long before = cache.databaseLoads();

        // Same Redis, empty local cache: what a freshly started instance sees.
        var cacheConfig = new SurgeProperties.Cache(Duration.ofSeconds(2), Duration.ofMillis(250));
        EventCache fresh = new EventCache(events, redis, JsonMapper.builder().build(),
                new SurgeProperties(null, null, null, cacheConfig, null, null));
        assertThat(fresh.get(eventId)).isPresent();
        assertThat(fresh.databaseLoads()).isZero();
        assertThat(cache.databaseLoads()).isEqualTo(before);
    }

    @Test
    void missingEventIsNotCachedAsAnything() {
        assertThat(cache.get(999_999)).isEmpty();
    }

    @Test
    void eventPageShowsLiveAvailability() throws Exception {
        String body = mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Final\",\"capacity\":40}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long eventId = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        inventory.reserve(eventId, "u1", 3, 4);

        mvc.perform(get("/events/{id}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Final"))
                .andExpect(jsonPath("$.available").value(37));
        mvc.perform(get("/events/{id}", 999_999)).andExpect(status().isNotFound());
    }
}
