package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.event.InventoryCounter;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ICheckAvailability {
    Mono<InventoryCounter> execute(UUID eventId, UUID ticketTypeId);
}
