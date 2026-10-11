package com.panther.ticketing.ports.in;

import com.panther.ticketing.model.event.EventDetails;
import reactor.core.publisher.Flux;

public interface IListEvents {
    Flux<EventDetails> execute();
}
