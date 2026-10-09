package com.panther.ticketing.usecase;

import com.panther.ticketing.model.audit.AuditLog;
import com.panther.ticketing.model.enums.OrderStatus;
import com.panther.ticketing.model.enums.ReservationStatus;
import com.panther.ticketing.model.enums.TicketStatus;
import com.panther.ticketing.model.exception.BusinessException;
import com.panther.ticketing.model.exception.NotFoundException;
import com.panther.ticketing.model.order.Order;
import com.panther.ticketing.model.reservation.Reservation;
import com.panther.ticketing.model.ticket.Ticket;
import com.panther.ticketing.ports.in.IProcessOrder;
import com.panther.ticketing.ports.out.AuditGateway;
import com.panther.ticketing.ports.out.EventRepository;
import com.panther.ticketing.ports.out.OrderRepository;
import com.panther.ticketing.ports.out.ReservationRepository;
import com.panther.ticketing.ports.out.TicketRepository;
import com.panther.ticketing.ports.out.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

public class ProcessOrderUseCase implements IProcessOrder {

    private static final Logger log = LoggerFactory.getLogger(ProcessOrderUseCase.class);

    private final OrderRepository orderRepository;
    private final ReservationRepository reservationRepository;
    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final AuditGateway auditGateway;
    private final TimeProvider timeProvider;

    public ProcessOrderUseCase(OrderRepository orderRepository,
                                ReservationRepository reservationRepository,
                                TicketRepository ticketRepository,
                                EventRepository eventRepository,
                                AuditGateway auditGateway,
                                TimeProvider timeProvider) {
        this.orderRepository = orderRepository;
        this.reservationRepository = reservationRepository;
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.auditGateway = auditGateway;
        this.timeProvider = timeProvider;
    }

    @Override
    public Mono<Void> execute(UUID orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(new NotFoundException("Order not found: " + orderId)))
                .flatMap(order -> {
                    // Idempotency: skip if already processed
                    if (order.status() == OrderStatus.CONFIRMED || order.status() == OrderStatus.FAILED) {
                        log.info("Order {} already in terminal state {}, skipping", orderId, order.status());
                        return Mono.empty();
                    }
                    if (order.status() == OrderStatus.PROCESSING) {
                        log.info("Order {} already PROCESSING, skipping duplicate", orderId);
                        return Mono.empty();
                    }
                    return processOrder(order);
                });
    }

    private Mono<Void> processOrder(Order order) {
        return orderRepository.updateStatusConditional(order.withStatus(OrderStatus.PROCESSING))
                .flatMap(processing -> reservationRepository.findById(processing.reservationId())
                        .switchIfEmpty(Mono.error(new NotFoundException("Reservation not found")))
                        .flatMap(reservation -> {
                            if (reservation.isExpired(timeProvider.now())) {
                                return failOrder(processing, "Reservation expired")
                                        .then(releaseReservation(reservation, processing));
                            }
                            return confirmOrder(processing, reservation);
                        }))
                .onErrorResume(ex -> {
                    log.error("Failed to process order {}: {}", order.id(), ex.getMessage());
                    return orderRepository.updateStatusConditional(order.withStatus(OrderStatus.FAILED)).then();
                });
    }

    private Mono<Void> confirmOrder(Order order, Reservation reservation) {
        return ticketRepository.findByReservationId(reservation.id())
                .collectList()
                .flatMap(tickets -> transitionTicketsToSold(tickets, order))
                .flatMap(tickets -> eventRepository.findInventoryCounter(order.eventId(), order.ticketTypeId())
                        .flatMap(counter -> eventRepository.decrementReservedIncrementSold(
                                order.eventId(), order.ticketTypeId(), tickets.size(), counter.version())))
                .flatMap(counter -> reservationRepository.updateStatusConditional(
                        reservation.confirm(), ReservationStatus.ACTIVE))
                .flatMap(res -> orderRepository.updateStatusConditional(order.withStatus(OrderStatus.CONFIRMED)))
                .flatMap(confirmed -> auditGateway.record(new AuditLog(
                        UUID.randomUUID(), "Order", confirmed.id(), "CONFIRMED",
                        "tickets sold", timeProvider.now())))
                .then();
    }

    private Mono<List<Ticket>> transitionTicketsToSold(List<Ticket> tickets, Order order) {
        return Flux.fromIterable(tickets)
                .flatMap(ticket -> {
                    Ticket pending = ticket.markPendingConfirmation();
                    return ticketRepository.updateStatusConditional(pending, TicketStatus.RESERVED)
                            .flatMap(t -> ticketRepository.updateStatusConditional(t.confirmSale(), TicketStatus.PENDING_CONFIRMATION));
                })
                .collectList();
    }

    private Mono<Void> failOrder(Order order, String reason) {
        return orderRepository.updateStatusConditional(order.withStatus(OrderStatus.FAILED))
                .flatMap(failed -> auditGateway.record(new AuditLog(
                        UUID.randomUUID(), "Order", failed.id(), "FAILED", reason, timeProvider.now())))
                .then();
    }

    private Mono<Void> releaseReservation(Reservation reservation, Order order) {
        return ticketRepository.findByReservationId(reservation.id())
                .flatMap(ticket -> ticketRepository.updateStatusConditional(ticket.release(), TicketStatus.RESERVED))
                .collectList()
                .flatMap(released -> eventRepository.findInventoryCounter(order.eventId(), order.ticketTypeId())
                        .flatMap(counter -> eventRepository.decrementReservedIncrementAvailable(
                                order.eventId(), order.ticketTypeId(), released.size(), counter.version())))
                .flatMap(counter -> reservationRepository.updateStatusConditional(
                        reservation.expire(), ReservationStatus.ACTIVE))
                .then();
    }
}
