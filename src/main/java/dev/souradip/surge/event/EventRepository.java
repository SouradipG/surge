package dev.souradip.surge.event;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EventRepository {

    private final JdbcClient jdbc;

    EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public EventDetails create(String name, int capacity) {
        EventDetails event = jdbc.sql("insert into events (name, capacity) values (:name, :capacity) returning id, name, capacity")
                .param("name", name)
                .param("capacity", capacity)
                .query(EventDetails.class)
                .single();
        jdbc.sql("insert into inventory (event_id, capacity) values (:id, :capacity)")
                .param("id", event.id())
                .param("capacity", capacity)
                .update();
        return event;
    }

    public Optional<EventDetails> find(long id) {
        return jdbc.sql("select id, name, capacity from events where id = :id")
                .param("id", id)
                .query(EventDetails.class)
                .optional();
    }
}
