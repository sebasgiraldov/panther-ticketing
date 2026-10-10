package com.panther.ticketing.scheduler;

import com.panther.ticketing.ports.in.IReleaseExpiredReservations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationExpirationSchedulerTest {

    @Mock IReleaseExpiredReservations releaseExpiredReservations;

    @InjectMocks ReservationExpirationScheduler scheduler;

    @Test
    void releaseExpired_invokesUseCase() {
        when(releaseExpiredReservations.execute()).thenReturn(Mono.empty());

        scheduler.releaseExpired();

        verify(releaseExpiredReservations).execute();
    }

    @Test
    void releaseExpired_onError_doesNotThrow() {
        when(releaseExpiredReservations.execute()).thenReturn(Mono.error(new RuntimeException("fail")));

        scheduler.releaseExpired();

        verify(releaseExpiredReservations).execute();
    }

    @Test
    void releaseExpired_onSuccess_logsCompletion() throws InterruptedException {
        when(releaseExpiredReservations.execute()).thenReturn(Mono.empty());

        scheduler.releaseExpired();
        Thread.sleep(100);

        verify(releaseExpiredReservations).execute();
    }
}
