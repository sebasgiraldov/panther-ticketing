package com.panther.ticketing.ports.out;

import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.reservation.Reservation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

public interface ReservationRepository {
    Mono<Reservation> save(Reservation reservation);
    Mono<Reservation> findById(UUID id);
    Flux<Reservation> findByStatusAndExpiresAtBefore(ReservationStatus status, LocalDateTime threshold);
    Mono<Reservation> updateStatusConditional(Reservation reservation, ReservationStatus expectedStatus);
}
