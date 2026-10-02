package com.idem.checkin.checkin;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class CheckInDtos {

    private CheckInDtos() {
    }

    /**
     * @param externalRegistrationId референс из QR гостя (или raw QR, если регистратор не выделяет ID)
     * @param correlationId          необязательный id для трассировки; можно передать заголовком X-Correlation-Id
     */
    public record CheckInRequest(
            @NotBlank @Size(max = 512) String externalRegistrationId,
            @Size(max = 100) String correlationId) {
    }

    /** Результат проверки на входе. HTTP 200 для всех бизнес-исходов; смотреть поле status. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CheckInResponse(
            CheckInStatus status,
            String reason,
            Participant participant,
            Provisioning provisioning,
            UUID activeJobId) {
    }

    public enum CheckInStatus {
        /** Билет валиден, выдана команда на запись. */
        VALID,
        /** Регистрация не найдена. */
        NOT_FOUND,
        /** Регистрация найдена, но пропускать нельзя (reason: TICKET_REFUNDED, REGISTRATION_CANCELLED ...). */
        INVALID_REGISTRATION,
        /** Гостю уже выдан активный бейдж. Перевыпуск — отдельный flow (пока не реализован). */
        BADGE_ALREADY_ISSUED,
        /** По этой регистрации уже идёт запись; provisioning есть, только если job выдан этой же стойке. */
        PROVISIONING_IN_PROGRESS,
        /** На этой стойке не закончен другой job (activeJobId). */
        STATION_BUSY
    }

    /** Что можно показать хосту. VISIBLE — с именем, ANONYMOUS — только факт валидности. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Participant(String visibility, String displayName) {
        static Participant visible(String name) {
            return new Participant("VISIBLE", name);
        }

        static Participant anonymous() {
            return new Participant("ANONYMOUS", null);
        }
    }

    /** Команда для Admin Hub. */
    public record Provisioning(UUID jobId, String payloadType, String payload, Instant expiresAt) {
    }
}
