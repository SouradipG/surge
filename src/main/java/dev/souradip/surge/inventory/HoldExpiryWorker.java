package dev.souradip.surge.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class HoldExpiryWorker {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryWorker.class);

    private final HoldService holds;

    HoldExpiryWorker(HoldService holds) {
        this.holds = holds;
    }

    @Scheduled(fixedDelayString = "${surge.holds.expiry-poll}")
    void run() {
        try {
            while (holds.expireDue() == 500) {
                // drain the backlog
            }
        } catch (RuntimeException e) {
            log.warn("hold expiry failed, will retry: {}", e.toString());
        }
    }
}
