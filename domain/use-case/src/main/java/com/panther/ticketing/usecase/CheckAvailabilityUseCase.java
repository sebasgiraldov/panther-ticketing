package com.panther.ticketing.usecase;

import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.ports.in.ICheckAvailability;
import com.panther.ticketing.ports.out.EventRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class CheckAvailabilityUseCase implements ICheckAvailability {

    private final EventRepository eventRepository;

    public CheckAvailabilityUseCase(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public Mono<InventoryCounter> execute(UUID eventId, UUID ticketTypeId) {
        return eventRepository.findInventoryCounter(eventId, ticketTypeId)
                .switchIfEmpty(Mono.error(new NotFoundException(
                        "Inventory not found for event=" + eventId + " type=" + ticketTypeId)));
    }
}
