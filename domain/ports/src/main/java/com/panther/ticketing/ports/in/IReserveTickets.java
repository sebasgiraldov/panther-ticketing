package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.reservation.Reservation;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IReserveTickets {
    Mono<Reservation> execute(UUID eventId, UUID ticketTypeId, int quantity, String customerRef);
}
