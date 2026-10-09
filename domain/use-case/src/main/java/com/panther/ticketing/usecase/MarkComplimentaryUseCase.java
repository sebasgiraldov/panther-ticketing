package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.exception.DomainException;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.model.exception.ConflictException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.ports.in.IMarkComplimentary;
import com.panther.ticketing.ports.out.AuditGateway;
import com.panther.ticketing.ports.out.TicketRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import reactor.core.publisher.Mono;

import java.util.UUID;

public class MarkComplimentaryUseCase implements IMarkComplimentary {

    private final TicketRepository ticketRepository;
    private final AuditGateway auditGateway;
    private final TimeProvider timeProvider;

    public MarkComplimentaryUseCase(TicketRepository ticketRepository,
                                     AuditGateway auditGateway,
                                     TimeProvider timeProvider) {
        this.ticketRepository = ticketRepository;
        this.auditGateway = auditGateway;
        this.timeProvider = timeProvider;
    }

    @Override
    public Mono<Ticket> execute(UUID ticketId, String ownerRef) {
        return ticketRepository.findById(ticketId)
                .flatMap(ticket -> applyComplimentary(ticket, ownerRef))
                .flatMap(updated -> ticketRepository.save(updated)
                        .flatMap(saved -> auditGateway.record(new AuditLog(
                                UUID.randomUUID(), "Ticket", saved.id(),
                                "COMPLIMENTARY", "owner=" + ownerRef, timeProvider.now()))
                                .thenReturn(saved)));
    }

    // Pattern matching sobre DomainException sealed hierarchy
    private Mono<Ticket> applyComplimentary(Ticket ticket, String ownerRef) {
        try {
            return Mono.just(ticket.markComplimentary(ownerRef));
        } catch (DomainException ex) {
            return switch (ex) {
                case BusinessException e -> Mono.error(new BusinessException(
                        "Cannot mark ticket as complimentary: " + e.getMessage()));
                case NotFoundException e -> Mono.error(e);
                case ConflictException e -> Mono.error(e);
            };
        }
    }
}
