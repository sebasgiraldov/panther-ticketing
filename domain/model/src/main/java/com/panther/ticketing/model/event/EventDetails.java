package com.panther.ticketing.model.event;

import java.util.List;

/**
 * Read aggregate for a single event enriched with its ticket types and their
 * current availability. Pure domain record with no framework dependencies.
 */
public record EventDetails(
        Event event,
        List<TicketTypeAvailability> ticketTypes
) {
    public record TicketTypeAvailability(
            TicketType ticketType,
            InventoryCounter availability
    ) {}
}
