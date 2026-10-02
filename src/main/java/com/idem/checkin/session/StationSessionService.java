package com.idem.checkin.session;

import com.idem.checkin.CheckInProperties;
import com.idem.checkin.api.ApiException;
import com.idem.checkin.security.Secrets;
import com.idem.checkin.session.SessionDtos.OpenSessionRequest;
import com.idem.checkin.session.SessionDtos.OpenSessionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class StationSessionService {

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
        requireApiKey(authorization);
        Instant now = clock.instant();
        String deviceId = request.adminHub().deviceId();
        repository.lockHub(deviceId);
        repository.closeActiveForHub(deviceId, now);

        String token = secrets.sessionToken();
        Instant expiresAt = now.plus(properties.stationAgent().sessionTtl());
        var session = new StationSession(UUID.randomUUID(), properties.eventId(), deviceId,
                request.adminHub().firmwareVersion(), request.adminHub().protocolVersion(),
                request.agent() == null ? null : request.agent().version(),
                StationSession.ACTIVE, null, now, now, expiresAt, null);
        repository.insert(session, Secrets.sha256(token));
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
        return repository.findByTokenHash(Secrets.sha256(token))
                .orElseThrow(() -> unauthorized("UNAUTHORIZED", "Unknown session token"));
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
        return new SessionDtos.SessionStateResponse(session.id(), StationSession.ACTIVE, expiresAt);
    }

    @Transactional
    public void close(StationSession session) {
        repository.close(session.id(), "CLIENT_CLOSED", clock.instant());
    }

    private void requireActive(StationSession session) {
        if (StationSession.CLOSED.equals(session.status())) {
            throw unauthorized("STATION_SESSION_CLOSED", "Station session is closed; open a new one");
        }
        if (!session.activeAt(clock.instant())) {
            throw unauthorized("STATION_SESSION_EXPIRED", "Station session expired; open a new one");
        }
    }

    private void requireApiKey(String authorization) {
        String key = bearer(authorization);
        boolean known = false;
        for (String candidate : properties.stationAgent().apiKeys()) {
            known |= key != null && Secrets.constantTimeEquals(candidate, key);
        }
        if (!known) {
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
