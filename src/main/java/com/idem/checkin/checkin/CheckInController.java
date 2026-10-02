package com.idem.checkin.checkin;

import com.idem.checkin.session.StationSessionService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Check-in на входе. Authorization: Bearer &lt;sessionToken&gt; активной Station Session. */
@RestController
class CheckInController {

    private final StationSessionService sessions;
    private final CheckInService service;

    CheckInController(StationSessionService sessions, CheckInService service) {
        this.sessions = sessions;
        this.service = service;
    }

    @PostMapping("/api/v1/check-ins")
    CheckInDtos.CheckInResponse checkIn(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                        @Valid @RequestBody CheckInDtos.CheckInRequest request) {
        var session = sessions.authenticateActive(authorization);
        String correlationId = request.correlationId() != null ? request.correlationId() : MDC.get(CorrelationIdFilter.MDC_KEY);
        return service.checkIn(session, request.externalRegistrationId().strip(), correlationId);
    }
}
