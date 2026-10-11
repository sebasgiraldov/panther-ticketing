package com.panther.ticketing.model.event;

import java.util.UUID;

/**
 * Aggregated availability counter per event/ticketType.
 * Used as fast-read index for availability queries and as first guard against overselling.
 * version is used for optimistic locking via DynamoDB conditional writes.
 */
public record InventoryCounter(
        UUID eventId,
        UUID ticketTypeId,
        int availableCount,
        int reservedCount,
        int soldCount,
        int complimentaryCount,
        long version
) {
    public int totalCount() {
        return availableCount + reservedCount + soldCount + complimentaryCount;
    }
}
