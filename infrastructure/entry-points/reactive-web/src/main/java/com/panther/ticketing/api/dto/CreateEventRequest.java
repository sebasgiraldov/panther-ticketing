package com.panther.ticketing.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CreateEventRequest(
        String name,
        String description,
        LocalDateTime date,
        String venue,
        int totalCapacity,
        List<TicketTypeRequest> ticketTypes
) {}
