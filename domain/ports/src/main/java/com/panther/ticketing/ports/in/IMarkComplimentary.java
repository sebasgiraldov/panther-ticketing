package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.ticket.Ticket;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IMarkComplimentary {
    Mono<Ticket> execute(UUID ticketId, String ownerRef);
}
