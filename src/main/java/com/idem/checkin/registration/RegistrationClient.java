package com.idem.checkin.registration;

import java.util.Optional;
import java.util.UUID;

/** Доступ к Registration Service. */
public interface RegistrationClient {

    /**
     * Найти регистрацию по референсу из QR.
     *
     * @return пусто, если такой регистрации нет
     * @throws AmbiguousReferenceException     если референс есть у нескольких регистраторов
     * @throws RegistrationUnavailableException если сервис не ответил
     */
    Optional<RegistrationInfo> lookup(UUID eventId, String externalRegistrationId);

    Optional<RegistrationInfo> get(UUID registrationId);

    /** Отметить check-in. Идемпотентно. */
    void markCheckedIn(UUID registrationId, java.time.Instant checkedInAt);

    class RegistrationUnavailableException extends RuntimeException {
        public RegistrationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class AmbiguousReferenceException extends RuntimeException {
        public AmbiguousReferenceException() {
            super("Reference matches several registrations");
        }
    }
}
