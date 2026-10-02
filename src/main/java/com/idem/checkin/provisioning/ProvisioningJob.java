package com.idem.checkin.provisioning;

import java.time.Instant;
import java.util.UUID;

public record ProvisioningJob(
        UUID id,
        UUID stationSessionId,
        UUID registrationId,
        UUID badgeId,
        String payloadType,
        String payload,
        JobStatus status,
        String errorCode,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant completedAt,
        boolean registrationSynced,
        String badgeStatus) {
}
