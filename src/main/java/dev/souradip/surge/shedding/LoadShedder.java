package dev.souradip.surge.shedding;

import dev.souradip.surge.SurgeProperties;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Caps requests in flight and reserves part of the capacity for checkout.
 *
 * <p>Under overload every request gets slower, timeouts pile up and clients retry, which adds
 * more load. Refusing some requests early and cheaply keeps the rest fast. Which ones to refuse
 * is a product decision: someone browsing the seat map can try again in a second, someone
 * halfway through paying should not be the one who gets dropped.
 */
@Component
public class LoadShedder {

    public enum Priority { BROWSE, CHECKOUT }

    private final AtomicInteger inFlight = new AtomicInteger();
    private final int maxInFlight;
    private final int browseLimit;

    LoadShedder(SurgeProperties properties) {
        this.maxInFlight = properties.shedding().maxInFlight();
        this.browseLimit = (int) Math.floor(maxInFlight * properties.shedding().browseShare());
    }

    /** @return true if the request may proceed; the caller must then call {@link #exit()} */
    public boolean tryEnter(Priority priority) {
        int limit = priority == Priority.CHECKOUT ? maxInFlight : browseLimit;
        while (true) {
            int current = inFlight.get();
            if (current >= limit) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void exit() {
        inFlight.decrementAndGet();
    }

    public int inFlight() {
        return inFlight.get();
    }
}
