package com.panther.ticketing.usecase;

import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.ports.in.ICreateEvent;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.TicketRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

public class CreateEventUseCase implements ICreateEvent {

    private final EventRepository eventRepository;
    private final TicketRepository ticketRepository;
    private final TimeProvider timeProvider;

    public CreateEventUseCase(EventRepository eventRepository,
                              TicketRepository ticketRepository,
                              TimeProvider timeProvider) {
        this.eventRepository = eventRepository;
        this.ticketRepository = ticketRepository;
        this.timeProvider = timeProvider;
    }

    @Override
    public Mono<Event> execute(Event event, List<TicketType> ticketTypes) {
        int totalTickets = ticketTypes.stream().mapToInt(TicketType::totalQuantity).sum();
        if (totalTickets > event.totalCapacity()) {
            return Mono.error(new BusinessException(
                    "Total ticket quantity (" + totalTickets + ") exceeds event capacity (" + event.totalCapacity() + ")"));
        }
        LocalDateTime now = timeProvider.now();
        Event stamped = event.createdAt() != null
                ? event
                : new Event(event.id(), event.name(), event.description(),
                        event.date(), event.venue(), event.totalCapacity(), now);
        return eventRepository.save(stamped)
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
                                        return eventRepository.saveInventoryCounter(counter)
                                                .then(generateTickets(saved.id(), savedTt, now));
                                    });
                        })
                        .then(Mono.just(saved)));
    }

    /**
     * Eagerly materialises {@code savedTt.totalQuantity()} individual tickets in
     * AVAILABLE status so every reservation/sale/release operates on real records
     * and reservation expiry is a literal RESERVED -> AVAILABLE transition.
     */
    private Mono<Void> generateTickets(UUID eventId, TicketType savedTt, LocalDateTime now) {
        List<Ticket> tickets = IntStream.range(0, savedTt.totalQuantity())
                .mapToObj(i -> new Ticket(
                        UUID.randomUUID(), eventId, savedTt.id(),
                        TicketStatus.AVAILABLE, savedTt.price(),
                        null, null, null, 0L, now, now))
                .toList();
        return ticketRepository.saveAll(tickets).then();
    }
}
