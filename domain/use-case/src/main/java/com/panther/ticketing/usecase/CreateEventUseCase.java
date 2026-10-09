package com.panther.ticketing.usecase;

import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.ports.in.ICreateEvent;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public class CreateEventUseCase implements ICreateEvent {

    private final EventRepository eventRepository;
    private final TimeProvider timeProvider;

    public CreateEventUseCase(EventRepository eventRepository, TimeProvider timeProvider) {
        this.eventRepository = eventRepository;
        this.timeProvider = timeProvider;
    }

    @Override
    public Mono<Event> execute(Event event, List<TicketType> ticketTypes) {
        return eventRepository.save(event)
                .flatMap(saved -> Flux.fromIterable(ticketTypes)
                        .flatMap(tt -> {
                            TicketType withEventId = new TicketType(
                                    tt.id() != null ? tt.id() : UUID.randomUUID(),
                                    saved.id(), tt.name(), tt.price(), tt.totalQuantity());
                            return eventRepository.saveTicketType(withEventId)
                                    .flatMap(savedTt -> {
                                        InventoryCounter counter = new InventoryCounter(
                                                saved.id(), savedTt.id(),
                                                savedTt.totalQuantity(), 0, 0, 0L);
                                        return eventRepository.saveInventoryCounter(counter);
                                    });
                        })
                        .then(Mono.just(saved)));
    }
}
