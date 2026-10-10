package com.idem.checkin.session;

import com.idem.checkin.CheckInProperties;
import com.idem.checkin.api.ApiException;
import com.idem.checkin.logging.Mdc;
import com.idem.checkin.security.Secrets;
import com.idem.checkin.session.SessionDtos.OpenSessionRequest;
import com.idem.checkin.session.SessionDtos.OpenSessionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class StationSessionService {

    private static final Logger log = LoggerFactory.getLogger(StationSessionService.class);
    private static final String BEARER = "Bearer ";

    private final StationSessionRepository repository;
    private final Secrets secrets;
    private final CheckInProperties properties;
    private final Clock clock;

    public StationSessionService(StationSessionRepository repository, Secrets secrets,
                                 CheckInProperties properties, Clock clock) {
        this.repository = repository;
        this.secrets = secrets;
        this.properties = properties;
        this.clock = clock;
    }

    /** Открыть сессию: Agent нашёл Admin Hub и предъявил API-ключ. */
    @Transactional
    public OpenSessionResponse open(String authorization, OpenSessionRequest request) {
        String deviceId = request.adminHub().deviceId();
        requireApiKey(authorization, deviceId);
        Instant now = clock.instant();
        repository.lockHub(deviceId);
        int replaced = repository.closeActiveForHub(deviceId, now);

        String token = secrets.sessionToken();
        Instant expiresAt = now.plus(properties.stationAgent().sessionTtl());
        var session = new StationSession(UUID.randomUUID(), properties.eventId(), deviceId,
                request.adminHub().firmwareVersion(), request.adminHub().protocolVersion(),
                request.agent() == null ? null : request.agent().version(),
                StationSession.ACTIVE, null, now, now, expiresAt, null);
        repository.insert(session, Secrets.sha256(token));
        MDC.put(Mdc.SESSION_ID, session.id().toString());
        log.info("Station session {} opened: Admin Hub {} (firmware {}, protocol {}), Agent {}, event {}, expires {}{}",
                session.id(), deviceId, session.adminHubFirmwareVersion(), session.protocolVersion(),
                session.agentVersion(), session.eventId(), expiresAt,
                replaced > 0 ? "; previous session of this Hub closed" : "");
        return new OpenSessionResponse(session.id(), token, session.eventId(), expiresAt,
                properties.stationAgent().heartbeatInterval().toSeconds());
    }

    /** Сессия по sessionToken в любом состоянии (нужно, чтобы досылать результаты старых jobs). */
    @Transactional(readOnly = true)
    public StationSession authenticate(String authorization) {
        String token = bearer(authorization);
        if (token == null) {
            throw unauthorized("UNAUTHORIZED", "Missing session token");
        }
        StationSession session = repository.findByTokenHash(Secrets.sha256(token))
                .orElseThrow(() -> unauthorized("UNAUTHORIZED", "Unknown session token"));
        MDC.put(Mdc.SESSION_ID, session.id().toString());
        return session;
    }

    /** Сессия, которая может принимать новые check-in. */
    @Transactional(readOnly = true)
    public StationSession authenticateActive(String authorization) {
        StationSession session = authenticate(authorization);
        requireActive(session);
        return session;
    }

    public StationSession requireOwn(StationSession session, UUID sessionId) {
        if (!session.id().equals(sessionId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Session token does not belong to this session");
        }
        return session;
    }

    @Transactional
    public SessionDtos.SessionStateResponse heartbeat(StationSession session) {
        requireActive(session);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.stationAgent().sessionTtl());
        repository.touch(session.id(), now, expiresAt);
        log.debug("Heartbeat: session {} (Admin Hub {}) extended until {}", session.id(), session.adminHubDeviceId(), expiresAt);
        return new SessionDtos.SessionStateResponse(session.id(), StationSession.ACTIVE, expiresAt);
    }

    @Transactional
    public void close(StationSession session) {
        if (repository.close(session.id(), "CLIENT_CLOSED", clock.instant()) > 0) {
            log.info("Station session {} (Admin Hub {}) closed by Station Agent", session.id(), session.adminHubDeviceId());
        } else {
            log.debug("Station session {} was already closed", session.id());
        }
    }

    private void requireActive(StationSession session) {
        if (StationSession.CLOSED.equals(session.status())) {
            log.info("Session {} (Admin Hub {}) is closed; Agent must open a new one", session.id(), session.adminHubDeviceId());
            throw unauthorized("STATION_SESSION_CLOSED", "Station session is closed; open a new one");
        }
        if (!session.activeAt(clock.instant())) {
            log.info("Session {} (Admin Hub {}) expired at {}; Agent must open a new one",
                    session.id(), session.adminHubDeviceId(), session.expiresAt());
            throw unauthorized("STATION_SESSION_EXPIRED", "Station session expired; open a new one");
        }
    }

    private void requireApiKey(String authorization, String deviceId) {
        String key = bearer(authorization);
        boolean known = false;
        for (String candidate : properties.stationAgent().apiKeys()) {
            known |= key != null && Secrets.constantTimeEquals(candidate, key);
        }
        if (!known) {
            log.warn("Station session for Admin Hub {} refused: {} Station Agent API key", deviceId, key == null ? "missing" : "invalid");
            throw unauthorized("UNAUTHORIZED", "Missing or invalid Station Agent API key");
        }
    }

    private static String bearer(String authorization) {
        return authorization != null && authorization.startsWith(BEARER) ? authorization.substring(BEARER.length()).strip() : null;
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }
}
