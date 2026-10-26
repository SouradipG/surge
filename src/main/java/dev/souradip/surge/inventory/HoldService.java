package dev.souradip.surge.inventory;

import dev.souradip.surge.SurgeProperties;
import dev.souradip.surge.api.ApiException;
import dev.souradip.surge.waitingroom.AdmissionTokens;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds and purchases.
 *
 * <pre>
 *   hold:      check admission token -> reserve in Redis (atomic) -> insert hold row in Postgres
 *                                                                     failed? release in Redis
 *   purchase:  HELD -> PURCHASED if not expired -> sold += qty (checked) -> order   (one transaction)
 *   expiry:    HELD -> EXPIRED once expires_at passes -> release in Redis
 * </pre>
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final InventoryStore inventory;
    private final HoldRepository holds;
    private final AdmissionTokens tokens;
    private final TransactionTemplate tx;
    private final SurgeProperties.Holds config;

    HoldService(InventoryStore inventory, HoldRepository holds, AdmissionTokens tokens, TransactionTemplate tx,
                SurgeProperties properties) {
        this.inventory = inventory;
        this.holds = holds;
        this.tokens = tokens;
        this.tx = tx;
        this.config = properties.holds();
    }

    public HoldView hold(long eventId, String userId, int quantity, String admissionToken) {
        tokens.verify(admissionToken, eventId, userId);

        switch (inventory.reserve(eventId, userId, quantity, config.maxPerUser())) {
            case SOLD_OUT -> throw new ApiException(HttpStatus.CONFLICT, "SOLD_OUT", "not enough tickets left");
            case LIMIT_REACHED -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "LIMIT_REACHED",
                    "at most " + config.maxPerUser() + " tickets per person");
            case NOT_INITIALISED -> throw new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_ON_SALE", "event is not on sale");
            case RESERVED -> {
                // continue
            }
        }

        try {
            return holds.insert(UUID.randomUUID(), eventId, userId, quantity, config.ttl());
        } catch (RuntimeException e) {
            // Compensate: the units were taken in Redis but no hold exists. If this process dies
            // before the release runs, the reconciler finds the missing units later.
            inventory.release(eventId, userId, quantity);
            throw e;
        }
    }

    public OrderView purchase(UUID holdId, String userId) {
        // A retry of a purchase that already went through returns the same order.
        var existing = holds.orderForHold(holdId);
        if (existing.isPresent()) {
            if (!existing.get().userId().equals(userId)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "no such hold");
            }
            return existing.get();
        }

        try {
            return tx.execute(status -> {
                var purchased = holds.markPurchased(holdId, userId);
                if (purchased.isEmpty()) {
                    return explainFailedPurchase(holdId, userId);
                }
                HoldView hold = purchased.get();
                holds.addSold(hold.eventId(), hold.quantity());
                return holds.insertOrder(hold);
            });
        } catch (DataIntegrityViolationException e) {
            // The no_oversell check refused the sale. Redis let one hold too many through,
            // which can only happen after the counters drifted. Postgres has the final word.
            log.error("no_oversell constraint rejected purchase of hold {}", holdId);
            throw new ApiException(HttpStatus.CONFLICT, "SOLD_OUT", "not enough tickets left");
        }
    }

    private OrderView explainFailedPurchase(UUID holdId, String userId) {
        var current = holds.find(holdId).filter(h -> h.userId().equals(userId));
        if (current.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "no such hold");
        }
        if (current.get().status().equals("PURCHASED")) {
            // A concurrent retry of the same purchase won the row lock and has committed by now.
            return holds.orderForHold(holdId).orElseThrow();
        }
        throw new ApiException(HttpStatus.CONFLICT, "HOLD_EXPIRED", "the hold expired before payment");
    }

    /** @return how many holds expired */
    public int expireDue() {
        List<HoldView> expired = tx.execute(status -> holds.expireDue(500));
        for (HoldView hold : expired) {
            try {
                inventory.release(hold.eventId(), hold.userId(), hold.quantity());
            } catch (RuntimeException e) {
                log.warn("could not release expired hold {} in redis, reconciler will repair: {}", hold.id(), e.toString());
            }
        }
        return expired.size();
    }
}
