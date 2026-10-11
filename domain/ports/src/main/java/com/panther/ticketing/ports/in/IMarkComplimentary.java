package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.reservation.Reservation;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IMarkComplimentary {
    Mono<Reservation> execute(UUID eventId, UUID reservationId, String ownerRef);
}
