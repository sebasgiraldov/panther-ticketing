package com.panther.ticketing.model.audit;

import java.time.LocalDateTime;
import java.util.UUID;

public record AuditLog(
        UUID id,
        String aggregateType,
        UUID aggregateId,
        String action,
        String detail,
        LocalDateTime timestamp
) {}
