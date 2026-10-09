package com.panther.ticketing.ports.in;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface IProcessOrder {
    Mono<Void> execute(UUID orderId);
}
