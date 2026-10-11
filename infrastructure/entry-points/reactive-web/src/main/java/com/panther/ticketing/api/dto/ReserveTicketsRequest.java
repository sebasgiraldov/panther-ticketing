package com.panther.ticketing.api.dto;

public record ReserveTicketsRequest(
        String ticketTypeId,
        int quantity,
        String customerRef
) {}
