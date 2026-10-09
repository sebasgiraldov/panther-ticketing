package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.TicketType;
import reactor.core.publisher.Mono;

import java.util.List;

public interface ICreateEvent {
    Mono<Event> execute(Event event, List<TicketType> ticketTypes);
}
