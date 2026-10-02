package com.idem.checkin.session;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Station Session: связка «ноутбук + Station Agent + Admin Hub» на время работы стойки. */
@RestController
@RequestMapping("/api/v1/station-sessions")
class StationSessionController {

    private final StationSessionService service;

    StationSessionController(StationSessionService service) {
        this.service = service;
    }

    /** Authorization: Bearer &lt;Station Agent API key&gt;. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    SessionDtos.OpenSessionResponse open(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                         @Valid @RequestBody SessionDtos.OpenSessionRequest request) {
        return service.open(authorization, request);
    }

    /** Authorization: Bearer &lt;sessionToken&gt;. Продлевает сессию. */
    @PostMapping("/{sessionId}/heartbeat")
    SessionDtos.SessionStateResponse heartbeat(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                               @PathVariable UUID sessionId) {
        return service.heartbeat(service.requireOwn(service.authenticate(authorization), sessionId));
    }

    /** Authorization: Bearer &lt;sessionToken&gt;. Закрывает сессию (Agent завершает работу или сменился Hub). */
    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
               @PathVariable UUID sessionId) {
        service.close(service.requireOwn(service.authenticate(authorization), sessionId));
    }
}
