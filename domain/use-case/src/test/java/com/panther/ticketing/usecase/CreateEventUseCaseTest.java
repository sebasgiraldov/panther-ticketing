package com.panther.ticketing.usecase;

import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.TicketRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateEventUseCaseTest {

    @Mock EventRepository eventRepository;
    @Mock TicketRepository ticketRepository;
    @Mock TimeProvider timeProvider;

    CreateEventUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CreateEventUseCase(eventRepository, ticketRepository, timeProvider);
        when(timeProvider.now()).thenReturn(LocalDateTime.of(2026, 1, 1, 10, 0));
    }

    @Test
    void execute_savesEventAndGeneratesTickets() {
        UUID eventId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        Event event = new Event(eventId, "Concert", "Desc", LocalDateTime.now(), "Venue", 5, null);
        TicketType tt = new TicketType(ttId, null, "General", BigDecimal.valueOf(100), 2);
        Event saved = new Event(eventId, "Concert", "Desc", LocalDateTime.now(), "Venue", 5, LocalDateTime.now());
        TicketType savedTt = new TicketType(ttId, eventId, "General", BigDecimal.valueOf(100), 2);
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 2, 0, 0, 0, 0L);

        when(eventRepository.save(any())).thenReturn(Mono.just(saved));
        when(eventRepository.saveTicketType(any())).thenReturn(Mono.just(savedTt));
        when(eventRepository.saveInventoryCounter(any())).thenReturn(Mono.just(counter));
        when(ticketRepository.saveAll(anyList())).thenReturn(Flux.empty());

        StepVerifier.create(useCase.execute(event, List.of(tt)))
                .expectNextMatches(e -> e.id().equals(eventId))
                .verifyComplete();
    }

    @Test
    void execute_withExistingCreatedAt_doesNotOverwrite() {
        UUID eventId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        LocalDateTime existing = LocalDateTime.of(2025, 6, 1, 0, 0);
        Event event = new Event(eventId, "Concert", "Desc", LocalDateTime.now(), "Venue", 5, existing);
        TicketType tt = new TicketType(ttId, null, "VIP", BigDecimal.valueOf(200), 1);
        TicketType savedTt = new TicketType(ttId, eventId, "VIP", BigDecimal.valueOf(200), 1);
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 1, 0, 0, 0, 0L);

        when(eventRepository.save(any())).thenReturn(Mono.just(event));
        when(eventRepository.saveTicketType(any())).thenReturn(Mono.just(savedTt));
        when(eventRepository.saveInventoryCounter(any())).thenReturn(Mono.just(counter));
        when(ticketRepository.saveAll(anyList())).thenReturn(Flux.empty());

        StepVerifier.create(useCase.execute(event, List.of(tt)))
                .expectNextMatches(e -> e.createdAt().equals(existing))
                .verifyComplete();
    }

    @Test
    @MockitoSettings(strictness = Strictness.LENIENT)
    void execute_totalTicketsExceedCapacity_throwsBusinessException() {
        UUID eventId = UUID.randomUUID();
        Event event = new Event(eventId, "Concert", "Desc", LocalDateTime.now(), "Venue", 3, null);
        TicketType tt = new TicketType(UUID.randomUUID(), null, "General", BigDecimal.valueOf(100), 5);

        StepVerifier.create(useCase.execute(event, List.of(tt)))
                .expectError(com.panther.ticketing.model.exception.BusinessException.class)
                .verify();
    }
}
