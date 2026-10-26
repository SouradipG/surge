package dev.souradip.surge;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("surge")
public record SurgeProperties(
        RateLimit rateLimit,
        WaitingRoom waitingRoom,
        Holds holds,
        Cache cache,
        Shedding shedding,
        Reconcile reconcile) {

    public record RateLimit(int capacity, int refillPerSecond) {}

    public record WaitingRoom(int admitPerTick, Duration tick, Duration tokenTtl, String secret) {}

    public record Holds(Duration ttl, int maxPerUser, Duration expiryPoll) {}

    public record Cache(Duration ttl, Duration localTtl) {}

    public record Shedding(int maxInFlight, double browseShare) {}

    public record Reconcile(Duration interval) {}
}
