package com.panther.ticketing.config;

import com.panther.ticketing.ports.in.*;
import com.panther.ticketing.ports.out.*;
import com.panther.ticketing.usecase.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

@Configuration
public class UseCaseConfig {

    @Bean
    public TimeProvider timeProvider() {
        return LocalDateTime::now;
    }

    @Bean
    public ICreateEvent createEventUseCase(
            EventRepository eventRepository, TicketRepository ticketRepository, TimeProvider timeProvider) {
        return new CreateEventUseCase(eventRepository, ticketRepository, timeProvider);
    }

    @Bean
    public IGetEvent getEventUseCase(EventRepository eventRepository) {
        return new GetEventUseCase(eventRepository);
    }

    @Bean
    public ICheckAvailability checkAvailabilityUseCase(EventRepository eventRepository) {
        return new CheckAvailabilityUseCase(eventRepository);
    }

    @Bean
    public IReserveTickets reserveTicketsUseCase(
            EventRepository eventRepository, TicketRepository ticketRepository,
            ReservationRepository reservationRepository, AuditGateway auditGateway,
            TimeProvider timeProvider,
            @Value("${reservation.minutes}") int reservationMinutes,
            @Value("${reservation.max-retries}") int maxRetries,
            @Value("${reservation.retry-backoff-ms}") long retryBackoffMs) {
        return new ReserveTicketsUseCase(eventRepository, ticketRepository,
                reservationRepository, auditGateway, timeProvider,
                reservationMinutes, maxRetries, retryBackoffMs);
    }

    @Bean
    public IPurchaseTickets purchaseTicketsUseCase(
            OrderRepository orderRepository, ReservationRepository reservationRepository,
            OrderQueueGateway orderQueueGateway, EventRepository eventRepository,
            TimeProvider timeProvider) {
        return new PurchaseTicketsUseCase(orderRepository, reservationRepository,
                orderQueueGateway, eventRepository, timeProvider);
    }

    @Bean
    public IGetOrderStatus getOrderStatusUseCase(OrderRepository orderRepository) {
        return new GetOrderStatusUseCase(orderRepository);
    }

    @Bean
    public IProcessOrder processOrderUseCase(
            OrderRepository orderRepository, ReservationRepository reservationRepository,
            TicketRepository ticketRepository, EventRepository eventRepository,
            AuditGateway auditGateway, TimeProvider timeProvider) {
        return new ProcessOrderUseCase(orderRepository, reservationRepository,
                ticketRepository, eventRepository, auditGateway, timeProvider);
    }

    @Bean
    public IReleaseExpiredReservations releaseExpiredReservationsUseCase(
            ReservationRepository reservationRepository, TicketRepository ticketRepository,
            EventRepository eventRepository, AuditGateway auditGateway, TimeProvider timeProvider) {
        return new ReleaseExpiredReservationsUseCase(reservationRepository, ticketRepository,
                eventRepository, auditGateway, timeProvider);
    }

    @Bean
    public IMarkComplimentary markComplimentaryUseCase(
            ReservationRepository reservationRepository, TicketRepository ticketRepository,
            EventRepository eventRepository, AuditGateway auditGateway, TimeProvider timeProvider) {
        return new MarkComplimentaryUseCase(reservationRepository, ticketRepository,
                eventRepository, auditGateway, timeProvider);
    }
}
