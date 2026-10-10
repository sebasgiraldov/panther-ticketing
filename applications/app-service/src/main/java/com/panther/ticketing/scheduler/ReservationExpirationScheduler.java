package com.panther.ticketing.scheduler;

import com.panther.ticketing.ports.in.IReleaseExpiredReservations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationExpirationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationScheduler.class);

    private final IReleaseExpiredReservations releaseExpiredReservations;

    public ReservationExpirationScheduler(IReleaseExpiredReservations releaseExpiredReservations) {
        this.releaseExpiredReservations = releaseExpiredReservations;
    }

    @Scheduled(fixedDelayString = "${reservation.scheduler.delay-ms:60000}")
    public void releaseExpired() {
        log.info("Running reservation expiration scheduler");
        releaseExpiredReservations.execute()
                .subscribe(
                        v -> log.info("Reservation expiration run completed"),
                        ex -> log.error("Reservation expiration run failed: {}", ex.getMessage()));
    }
}
