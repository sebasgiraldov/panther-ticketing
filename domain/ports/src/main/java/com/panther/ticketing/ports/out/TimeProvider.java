package com.panther.ticketing.ports.out;

import java.time.LocalDateTime;

public interface TimeProvider {
    LocalDateTime now();
}
