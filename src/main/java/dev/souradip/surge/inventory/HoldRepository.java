package dev.souradip.surge.inventory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class HoldRepository {

    private final JdbcClient jdbc;

    HoldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    HoldView insert(UUID id, long eventId, String userId, int quantity, Duration ttl) {
        return jdbc.sql("""
                        insert into holds (id, event_id, user_id, quantity, status, expires_at)
                        values (:id, :event, :user, :qty, 'HELD', now() + make_interval(secs => :ttl))
                        returning id, event_id, user_id, quantity, status, expires_at
                        """)
                .param("id", id)
                .param("event", eventId)
                .param("user", userId)
                .param("qty", quantity)
                .param("ttl", ttl.toMillis() / 1000.0)
                .query(HoldView.class)
                .single();
    }

    public Optional<HoldView> find(UUID id) {
        return jdbc.sql("select id, event_id, user_id, quantity, status, expires_at from holds where id = :id")
                .param("id", id)
                .query(HoldView.class)
                .optional();
    }

    /**
     * HELD to PURCHASED, only while the hold is still valid. The expiry worker runs the mirror
     * image of this update, and both wait on the same row lock, so for any hold exactly one of
     * the two wins.
     */
    Optional<HoldView> markPurchased(UUID id, String userId) {
        return jdbc.sql("""
                        update holds set status = 'PURCHASED', updated_at = now()
                        where id = :id and user_id = :user and status = 'HELD' and expires_at > now()
                        returning id, event_id, user_id, quantity, status, expires_at
                        """)
                .param("id", id)
                .param("user", userId)
                .query(HoldView.class)
                .optional();
    }

    List<HoldView> expireDue(int limit) {
        return jdbc.sql("""
                        update holds set status = 'EXPIRED', updated_at = now()
                        where id in (
                            select id from holds where status = 'HELD' and expires_at <= now()
                            order by expires_at limit :limit
                            for update skip locked)
                        returning id, event_id, user_id, quantity, status, expires_at
                        """)
                .param("limit", limit)
                .query(HoldView.class)
                .list();
    }

    void addSold(long eventId, int quantity) {
        jdbc.sql("update inventory set sold = sold + :qty where event_id = :event")
                .param("qty", quantity)
                .param("event", eventId)
                .update();
    }

    OrderView insertOrder(HoldView hold) {
        return jdbc.sql("""
                        insert into orders (id, hold_id, event_id, user_id, quantity)
                        values (:id, :hold, :event, :user, :qty)
                        returning id, hold_id, event_id, user_id, quantity, created_at
                        """)
                .param("id", UUID.randomUUID())
                .param("hold", hold.id())
                .param("event", hold.eventId())
                .param("user", hold.userId())
                .param("qty", hold.quantity())
                .query(OrderView.class)
                .single();
    }

    Optional<OrderView> orderForHold(UUID holdId) {
        return jdbc.sql("select id, hold_id, event_id, user_id, quantity, created_at from orders where hold_id = :hold")
                .param("hold", holdId)
                .query(OrderView.class)
                .optional();
    }
}
