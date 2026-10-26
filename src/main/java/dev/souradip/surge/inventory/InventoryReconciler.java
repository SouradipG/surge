package dev.souradip.surge.inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the Redis counters from Postgres.
 *
 * <p>Redis and Postgres can drift: a process dies between reserving in Redis and inserting the
 * hold, a Redis failover loses the last writes that had not reached the replica, or someone
 * flushes the wrong database. The truth is in Postgres: available = capacity - sold - active
 * holds. The overwrite is conditional on Redis's ops counter not having moved since we read it,
 * so a reserve that lands mid reconcile is never wiped out.
 */
@Component
public class InventoryReconciler {

    private static final Logger log = LoggerFactory.getLogger(InventoryReconciler.class);

    private final JdbcClient jdbc;
    private final InventoryStore inventory;

    InventoryReconciler(JdbcClient jdbc, InventoryStore inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    @Scheduled(fixedDelayString = "${surge.reconcile.interval}")
    void all() {
        for (Long eventId : jdbc.sql("select event_id from inventory").query(Long.class).list()) {
            try {
                reconcile(eventId);
            } catch (RuntimeException e) {
                log.warn("reconcile of event {} failed: {}", eventId, e.toString());
            }
        }
    }

    /** @return true if Redis now matches Postgres */
    public boolean reconcile(long eventId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String ops = inventory.opsVersion(eventId);
            long capacity = jdbc.sql("select capacity - sold from inventory where event_id = :id")
                    .param("id", eventId).query(Long.class).single();
            Map<String, Integer> perUser = new HashMap<>();
            List<UserQty> held = jdbc.sql("""
                            select user_id, sum(quantity)::int as qty from holds
                            where event_id = :id and status in ('HELD', 'PURCHASED')
                            group by user_id
                            """)
                    .param("id", eventId)
                    .query(UserQty.class)
                    .list();
            held.forEach(u -> perUser.put(u.userId(), u.qty()));
            long activeHolds = jdbc.sql("select coalesce(sum(quantity), 0) from holds where event_id = :id and status = 'HELD'")
                    .param("id", eventId).query(Long.class).single();

            long available = capacity - activeHolds;
            if (inventory.overwrite(eventId, ops, available, perUser)) {
                return true;
            }
        }
        log.info("event {} too busy to reconcile right now, will try next pass", eventId);
        return false;
    }

    record UserQty(String userId, int qty) {}
}
