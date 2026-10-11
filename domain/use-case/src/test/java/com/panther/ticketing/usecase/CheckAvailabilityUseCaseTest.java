package com.panther.ticketing.usecase;

import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.ports.out.EventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckAvailabilityUseCaseTest {

    @Mock EventRepository eventRepository;

    CheckAvailabilityUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new CheckAvailabilityUseCase(eventRepository);
    }

    @Test
    void execute_returnsCounter() {
        UUID eventId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        InventoryCounter counter = new InventoryCounter(eventId, ttId, 10, 2, 1, 0, 0L);
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.just(counter));

        StepVerifier.create(useCase.execute(eventId, ttId))
                .expectNext(counter)
                .verifyComplete();
    }

    @Test
    void execute_notFound_throwsNotFoundException() {
        UUID eventId = UUID.randomUUID();
        UUID ttId = UUID.randomUUID();
        when(eventRepository.findInventoryCounter(eventId, ttId)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(eventId, ttId))
                .expectError(NotFoundException.class)
                .verify();
    }
}
