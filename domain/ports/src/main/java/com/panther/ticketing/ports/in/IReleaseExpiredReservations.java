package com.panther.ticketing.ports.in;

import reactor.core.publisher.Mono;

public interface IReleaseExpiredReservations {
    Mono<Void> execute();
}
