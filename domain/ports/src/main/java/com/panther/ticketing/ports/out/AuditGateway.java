package com.panther.ticketing.ports.out;

import com.panther.ticketing.model.audit.AuditLog;
import reactor.core.publisher.Mono;

public interface AuditGateway {
    Mono<Void> record(AuditLog log);
}
