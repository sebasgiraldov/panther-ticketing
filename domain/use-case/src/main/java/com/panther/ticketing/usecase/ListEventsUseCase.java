package com.panther.ticketing.usecase;

import com.panther.ticketing.model.event.EventDetails;
import com.panther.ticketing.model.event.EventDetails.TicketTypeAvailability;
import com.panther.ticketing.ports.in.IListEvents;
import com.panther.ticketing.ports.out.EventRepository;
import reactor.core.publisher.Flux;

public class ListEventsUseCase implements IListEvents {

    private final EventRepository eventRepository;

    public ListEventsUseCase(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    @Override
    public Flux<EventDetails> execute() {
        return eventRepository.findAll()
                .flatMap(event -> eventRepository.findTicketTypesByEventId(event.id())
                        .flatMap(tt -> eventRepository.findInventoryCounter(event.id(), tt.id())
                                .map(counter -> new TicketTypeAvailability(tt, counter)))
                        .collectList()
                        .map(ticketTypes -> new EventDetails(event, ticketTypes)));
    }
}
