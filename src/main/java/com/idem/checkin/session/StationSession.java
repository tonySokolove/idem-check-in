package com.idem.checkin.session;

import java.time.Instant;
import java.util.UUID;

public record StationSession(
        UUID id,
        UUID eventId,
        String adminHubDeviceId,
        String adminHubFirmwareVersion,
        Integer protocolVersion,
        String agentVersion,
        String status,
        String closeReason,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant closedAt) {

    public static final String ACTIVE = "ACTIVE";
    public static final String CLOSED = "CLOSED";

    public boolean activeAt(Instant now) {
        return ACTIVE.equals(status) && expiresAt.isAfter(now);
    }
}
