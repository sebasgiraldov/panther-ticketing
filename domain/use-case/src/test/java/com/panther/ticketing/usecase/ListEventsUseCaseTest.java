package com.panther.ticketing.usecase;

import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.ports.out.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListEventsUseCaseTest {

    @Mock EventRepository eventRepository;

    ListEventsUseCase useCase;

    UUID eventId = UUID.randomUUID();
    UUID ttId = UUID.randomUUID();
    LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);

    @BeforeEach
    void setUp() {
        useCase = new ListEventsUseCase(eventRepository);
    }

    @Test
    void execute_returnsEventDetails() {
        Event event = new Event(eventId, "Concert", "Desc", now, "Venue", 10, now);
        TicketType tt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(50), 10);
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 10, 0, 0, 0, 0L);

        when(eventRepository.findAll()).thenReturn(Flux.just(event));
        when(eventRepository.findTicketTypesByEventId(eventId)).thenReturn(Flux.just(tt));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));

        StepVerifier.create(useCase.execute())
                .expectNextMatches(d -> d.event().id().equals(eventId) && d.ticketTypes().size() == 1)
                .verifyComplete();
    }

    @Test
    void execute_noEvents_returnsEmpty() {
        when(eventRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(useCase.execute())
                .verifyComplete();
    }
}
