package com.idem.checkin.session;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class SessionDtos {

    private SessionDtos() {
    }

    public record OpenSessionRequest(@NotNull @Valid AdminHub adminHub, @Valid Agent agent) {
    }

    /** Данные из HELLO_RESPONSE Admin Hub. */
    public record AdminHub(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String deviceId,
            @Size(max = 64) String firmwareVersion,
            @Positive @Max(1000) Integer protocolVersion) {
    }

    public record Agent(@Size(max = 64) String version) {
    }

    public record OpenSessionResponse(
            UUID sessionId,
            String sessionToken,
            UUID eventId,
            Instant expiresAt,
            long heartbeatIntervalSeconds) {
    }

    public record SessionStateResponse(UUID sessionId, String status, Instant expiresAt) {
    }
}
