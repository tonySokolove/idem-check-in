package com.idem.checkin.provisioning;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ProvisioningDtos {

    private ProvisioningDtos() {
    }

    /**
     * Статус от Agent.
     *
     * @param readBackPayload обязателен для COMPLETED: что Admin Hub прочитал с метки после записи
     * @param error           для FAILED / CANCELLED
     */
    public record StatusReport(
            @NotNull JobStatus status,
            Instant occurredAt,
            @Size(max = 2048) String readBackPayload,
            @Valid Error error) {
    }

    public record Error(
            @Pattern(regexp = "[A-Z0-9_]{1,64}") String code,
            @Size(max = 500) String message) {
    }

    /** Состояние job для Agent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JobView(
            UUID jobId,
            JobStatus status,
            String payloadType,
            String payload,
            Instant expiresAt,
            String badgeStatus,
            Error error,
            Instant updatedAt) {

        static JobView of(ProvisioningJob job) {
            return new JobView(job.id(), job.status(), job.payloadType(), job.payload(), job.expiresAt(),
                    job.badgeStatus(),
                    job.errorCode() == null ? null : new Error(job.errorCode(), job.errorMessage()),
                    job.updatedAt());
        }
    }
}
