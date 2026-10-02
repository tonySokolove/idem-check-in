package com.idem.checkin.registration;

import java.time.Instant;
import java.util.UUID;

/** Ответ Registration Service (внутренний API). */
public record RegistrationInfo(
        UUID registrationId,
        UUID eventId,
        String dataAccessMode,
        String status,
        String ticketStatus,
        boolean ticketValid,
        String displayName,
        Instant registeredAt,
        Instant checkedInAt) {

    public boolean admissible() {
        return "ACTIVE".equals(status) && ticketValid;
    }

    /** Почему гостя нельзя пропустить — для поля reason в ответе check-in. */
    public String rejectionReason() {
        if (!"ACTIVE".equals(status)) {
            return "REGISTRATION_" + status;
        }
        return ticketStatus == null || "VALID".equals(ticketStatus) ? "TICKET_NOT_VALID" : "TICKET_" + ticketStatus;
    }

    /** VISIBLE только если регистратор передал данные (FULL) и есть что показать. */
    public boolean visible() {
        return "FULL".equals(dataAccessMode) && displayName != null && !displayName.isBlank();
    }
}
