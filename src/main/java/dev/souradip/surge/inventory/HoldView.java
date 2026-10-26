package dev.souradip.surge.inventory;

import java.time.OffsetDateTime;
import java.util.UUID;

public record HoldView(UUID id, long eventId, String userId, int quantity, String status, OffsetDateTime expiresAt) {}
