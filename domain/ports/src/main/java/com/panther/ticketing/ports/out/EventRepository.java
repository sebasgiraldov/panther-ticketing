package com.panther.ticketing.ports.out;

import com.panther.ticketing.model.event.Event;
import com.panther.ticketing.model.event.InventoryCounter;
import com.panther.ticketing.model.event.TicketType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface EventRepository {
    Mono<Event> save(Event event);
    Mono<Event> findById(UUID id);
    Mono<TicketType> saveTicketType(TicketType ticketType);
    Flux<TicketType> findTicketTypesByEventId(UUID eventId);
    Mono<TicketType> findTicketTypeById(UUID id);
    Mono<InventoryCounter> findInventoryCounter(UUID eventId, UUID ticketTypeId);
    Mono<InventoryCounter> saveInventoryCounter(InventoryCounter counter);
    /**
     * Atomically decrements availableCount and increments reservedCount.
     * Uses conditional write: availableCount >= quantity AND version == expectedVersion.
     * Throws ConflictException if condition fails.
     */
    Mono<InventoryCounter> decrementAvailableIncrementReserved(UUID eventId, UUID ticketTypeId, int quantity, long expectedVersion);
    /**
     * Atomically decrements reservedCount and increments soldCount.
     */
    Mono<InventoryCounter> decrementReservedIncrementSold(UUID eventId, UUID ticketTypeId, int quantity, long expectedVersion);
    /**
     * Atomically decrements reservedCount and increments availableCount (release).
     */
    Mono<InventoryCounter> decrementReservedIncrementAvailable(UUID eventId, UUID ticketTypeId, int quantity, long expectedVersion);
    /**
     * Atomically decrements reservedCount for a complimentary grant.
     * The tickets were already moved AVAILABLE->RESERVED at reservation time, so now
     * they leave RESERVED but are NOT a sale: soldCount and availableCount are untouched
     * (COMPLIMENTARY is final and non-countable). Conditional write:
     * reservedCount >= quantity AND version == expectedVersion.
     */
    Mono<InventoryCounter> decrementReservedForComplimentary(UUID eventId, UUID ticketTypeId, int quantity, long expectedVersion);
}
