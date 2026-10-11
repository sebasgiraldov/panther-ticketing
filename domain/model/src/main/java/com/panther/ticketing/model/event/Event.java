package com.panther.ticketing.model.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record Event(
        UUID id,
        String name,
        String description,
        LocalDateTime date,
        String venue,
        int totalCapacity,
        LocalDateTime createdAt
) {}
