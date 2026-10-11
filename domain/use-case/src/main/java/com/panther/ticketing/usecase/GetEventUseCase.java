package com.panther.ticketing.usecase;

import com.panther.ticketing.model.event.EventDetails;
import com.panther.ticketing.model.event.EventDetails.TicketTypeAvailability;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.ports.in.IGetEvent;
import com.panther.ticketing.ports.out.EventRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class GetEventUseCase implements IGetEvent {

    private final EventRepository eventRepository;

    public GetEventUseCase(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public Mono<EventDetails> execute(UUID eventId) {
        return eventRepository.findById(eventId)
                .switchIfEmpty(Mono.error(new NotFoundException("Event not found: " + eventId)))
                .flatMap(event -> eventRepository.findTicketTypesByEventId(eventId)
                        .flatMap(tt -> eventRepository.findInventoryCounter(eventId, tt.id())
                                .map(counter -> new TicketTypeAvailability(tt, counter)))
                        .collectList()
                        .map(ticketTypes -> new EventDetails(event, ticketTypes)));
    }
}
