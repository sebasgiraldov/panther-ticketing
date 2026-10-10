package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.event.EventDetails;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IGetEvent {
    Mono<EventDetails> execute(UUID eventId);
}
