package com.idem.checkin.checkin;

import com.idem.checkin.api.ApiException;
import com.idem.checkin.checkin.CheckInDtos.CheckInResponse;
import com.idem.checkin.checkin.CheckInDtos.CheckInStatus;
import com.idem.checkin.checkin.CheckInDtos.Participant;
import com.idem.checkin.checkin.CheckInDtos.Provisioning;
import com.idem.checkin.provisioning.ProvisioningJob;
import com.idem.checkin.provisioning.ProvisioningService;
import com.idem.checkin.provisioning.ProvisioningService.StartResult;
import com.idem.checkin.registration.RegistrationClient;
import com.idem.checkin.registration.RegistrationInfo;
import com.idem.checkin.session.StationSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class CheckInService {

    private static final Logger log = LoggerFactory.getLogger(CheckInService.class);

    private final RegistrationClient registrations;
    private final ProvisioningService provisioning;

    public CheckInService(RegistrationClient registrations, ProvisioningService provisioning) {
        this.registrations = registrations;
        this.provisioning = provisioning;
    }

    /**
     * Проверка билета на входе:
     * 1) локально в Registration Service;
     * 2–3) онлайн-проверка у регистратора и подписанный QR через Registrar Integration Service — ещё не реализованы;
     * 4) NOT_FOUND.
     */
    public CheckInResponse checkIn(StationSession session, String externalRegistrationId, String correlationId) {
        Optional<RegistrationInfo> found;
        try {
            found = registrations.lookup(session.eventId(), externalRegistrationId);
        } catch (RegistrationClient.AmbiguousReferenceException ambiguous) {
            return new CheckInResponse(CheckInStatus.INVALID_REGISTRATION, "AMBIGUOUS_REFERENCE", null, null, null);
        } catch (RegistrationClient.RegistrationUnavailableException unavailable) {
            log.warn("Check-in failed: {}", unavailable.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRATION_UNAVAILABLE",
                    "Registration Service is unavailable; retry later");
        }
        if (found.isEmpty()) {
            return new CheckInResponse(CheckInStatus.NOT_FOUND, null, null, null, null);
        }

        RegistrationInfo registration = found.get();
        Participant participant = registration.visible()
                ? Participant.visible(registration.displayName())
                : Participant.anonymous();
        if (!registration.admissible()) {
            return new CheckInResponse(CheckInStatus.INVALID_REGISTRATION, registration.rejectionReason(),
                    participant, null, null);
        }

        StartResult result = provisioning.start(session, registration.registrationId(), correlationId);
        return switch (result) {
            case StartResult.Started started ->
                    new CheckInResponse(CheckInStatus.VALID, null, participant, command(started.job()), null);
            case StartResult.AlreadyIssued ignored ->
                    new CheckInResponse(CheckInStatus.BADGE_ALREADY_ISSUED, null, participant, null, null);
            case StartResult.InProgress progress -> new CheckInResponse(CheckInStatus.PROVISIONING_IN_PROGRESS, null,
                    participant, progress.sameStation() ? command(progress.job()) : null, null);
            case StartResult.StationBusy busy ->
                    new CheckInResponse(CheckInStatus.STATION_BUSY, null, null, null, busy.activeJobId());
        };
    }

    private static Provisioning command(ProvisioningJob job) {
        return new Provisioning(job.id(), job.payloadType(), job.payload(), job.expiresAt());
    }
}
