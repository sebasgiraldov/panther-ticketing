package com.panther.ticketing.usecase;

import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.exception.NotFoundException;
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
class GetEventUseCaseTest {

    @Mock EventRepository eventRepository;

    GetEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetEventUseCase(eventRepository);
    }

    @Test
    void execute_returnsEventDetails() {
        UUID eventId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        Event event = new Event(eventId, "Concert", "Desc", LocalDateTime.now(), "Venue", 10, LocalDateTime.now());
        TicketType tt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(50), 10);
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 8, 2, 0, 0L);

        when(eventRepository.findById(eventId)).thenReturn(Mono.just(event));
        when(eventRepository.findTicketTypesByEventId(eventId)).thenReturn(Flux.just(tt));
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));

        StepVerifier.create(useCase.execute(eventId))
                .expectNextMatches(d -> d.event().equals(event) && d.ticketTypes().size() == 1)
                .verifyComplete();
    }

    @Test
    void execute_eventNotFound_throwsNotFoundException() {
        UUID eventId = UUID.randomUUID();
        when(eventRepository.findById(eventId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(eventId))
                .expectError(NotFoundException.class)
                .verify();
    }
}
