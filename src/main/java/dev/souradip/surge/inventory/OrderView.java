package dev.souradip.surge.inventory;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OrderView(UUID id, UUID holdId, long eventId, String userId, int quantity, OffsetDateTime createdAt) {}
