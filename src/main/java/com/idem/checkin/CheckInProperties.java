package com.idem.checkin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Настройки Check-in Service.
 *
 * @param eventId          ВРЕМЕННО: Event для всех Station Session, пока Device Service не сопоставляет Admin Hub с Event
 * @param cardBaseUrl      начало ссылки, которая записывается на NFC-бейдж; к ней добавляется public token
 * @param stationAgent     ключи и сроки для Station Agent
 * @param provisioningJobTtl сколько времени после выдачи job у Admin Hub есть, чтобы начать запись
 * @param registration     как ходить в Registration Service
 */
@ConfigurationProperties("checkin")
public record CheckInProperties(
        UUID eventId,
        URI cardBaseUrl,
        StationAgent stationAgent,
        Duration provisioningJobTtl,
        Registration registration) {

    static final int MIN_SECRET_LENGTH = 32;

    public CheckInProperties {
        require(eventId != null, "checkin.event-id (CHECKIN_EVENT_ID) is required");
        require(cardBaseUrl != null && "https".equals(cardBaseUrl.getScheme()) && cardBaseUrl.toString().endsWith("/"),
                "checkin.card-base-url must be an https URL ending with /");
        require(stationAgent != null, "checkin.station-agent is required");
        require(registration != null && registration.baseUrl() != null, "checkin.registration.base-url is required");
        require(registration.token() != null && registration.token().length() >= MIN_SECRET_LENGTH,
                "checkin.registration.token (REGISTRATION_API_TOKEN) must be at least " + MIN_SECRET_LENGTH + " characters");
        provisioningJobTtl = provisioningJobTtl == null ? Duration.ofMinutes(2) : provisioningJobTtl;
    }

    /**
     * @param apiKeys            ключи, с которыми Agent может открыть Station Session (STATION_AGENT_API_KEYS через запятую)
     * @param sessionTtl         сессия живёт столько без heartbeat
     * @param heartbeatInterval  как часто Agent должен присылать heartbeat
     */
    public record StationAgent(List<String> apiKeys, Duration sessionTtl, Duration heartbeatInterval) {
        public StationAgent {
            apiKeys = apiKeys == null ? List.of() : apiKeys.stream().map(String::strip).filter(k -> !k.isEmpty()).toList();
            require(!apiKeys.isEmpty(), "checkin.station-agent.api-keys (STATION_AGENT_API_KEYS) must not be empty");
            require(apiKeys.stream().allMatch(k -> k.length() >= MIN_SECRET_LENGTH),
                    "Station Agent API keys must be at least " + MIN_SECRET_LENGTH + " characters");
            sessionTtl = sessionTtl == null ? Duration.ofMinutes(5) : sessionTtl;
            heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(60) : heartbeatInterval;
            require(heartbeatInterval.compareTo(sessionTtl) < 0, "heartbeat-interval must be shorter than session-ttl");
        }
    }

    public record Registration(URI baseUrl, String token, Duration connectTimeout, Duration readTimeout) {
        public Registration {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            readTimeout = readTimeout == null ? Duration.ofSeconds(3) : readTimeout;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
