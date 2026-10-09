package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.event.Event;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IGetEvent {
    Mono<Event> execute(UUID eventId);
}
