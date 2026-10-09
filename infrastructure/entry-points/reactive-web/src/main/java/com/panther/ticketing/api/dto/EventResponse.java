package com.panther.ticketing.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String name,
        String description,
        LocalDateTime date,
        String venue,
        int totalCapacity,
        LocalDateTime createdAt
) {}
