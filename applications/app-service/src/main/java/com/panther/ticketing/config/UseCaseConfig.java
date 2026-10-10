package com.panther.ticketing.config;

import com.panther.ticketing.ports.out.*;
import com.panther.ticketing.usecase.*;
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
    public com.panther.ticketing.ports.in.ICreateEvent createEventUseCase(
            EventRepository eventRepository, TicketRepository ticketRepository, TimeProvider timeProvider) {
        return new CreateEventUseCase(eventRepository, ticketRepository, timeProvider);
    }

    @Bean
    public com.panther.ticketing.ports.in.IGetEvent getEventUseCase(EventRepository eventRepository) {
        return new GetEventUseCase(eventRepository);
    }

    @Bean
    public com.panther.ticketing.ports.in.ICheckAvailability checkAvailabilityUseCase(EventRepository eventRepository) {
        return new CheckAvailabilityUseCase(eventRepository);
    }

    @Bean
    public com.panther.ticketing.ports.in.IReserveTickets reserveTicketsUseCase(
            EventRepository eventRepository, TicketRepository ticketRepository,
            ReservationRepository reservationRepository, AuditGateway auditGateway,
            TimeProvider timeProvider) {
        return new ReserveTicketsUseCase(eventRepository, ticketRepository,
                reservationRepository, auditGateway, timeProvider);
    }

    @Bean
    public com.panther.ticketing.ports.in.IPurchaseTickets purchaseTicketsUseCase(
            OrderRepository orderRepository, ReservationRepository reservationRepository,
            OrderQueueGateway orderQueueGateway, TimeProvider timeProvider) {
        return new PurchaseTicketsUseCase(orderRepository, reservationRepository,
                orderQueueGateway, timeProvider);
    }

    @Bean
    public com.panther.ticketing.ports.in.IGetOrderStatus getOrderStatusUseCase(OrderRepository orderRepository) {
        return new GetOrderStatusUseCase(orderRepository);
    }

    @Bean
    public com.panther.ticketing.ports.in.IProcessOrder processOrderUseCase(
            OrderRepository orderRepository, ReservationRepository reservationRepository,
            TicketRepository ticketRepository, EventRepository eventRepository,
            AuditGateway auditGateway, TimeProvider timeProvider) {
        return new ProcessOrderUseCase(orderRepository, reservationRepository,
                ticketRepository, eventRepository, auditGateway, timeProvider);
    }

    @Bean
    public com.panther.ticketing.ports.in.IReleaseExpiredReservations releaseExpiredReservationsUseCase(
            ReservationRepository reservationRepository, TicketRepository ticketRepository,
            EventRepository eventRepository, AuditGateway auditGateway, TimeProvider timeProvider) {
        return new ReleaseExpiredReservationsUseCase(reservationRepository, ticketRepository,
                eventRepository, auditGateway, timeProvider);
    }

    @Bean
    public com.panther.ticketing.ports.in.IMarkComplimentary markComplimentaryUseCase(
            TicketRepository ticketRepository, AuditGateway auditGateway, TimeProvider timeProvider) {
        return new MarkComplimentaryUseCase(ticketRepository, auditGateway, timeProvider);
    }
}
