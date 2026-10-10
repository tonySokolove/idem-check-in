package com.idem.checkin.logging;

import com.idem.checkin.CheckInProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Сводка при старте: версия и действующие настройки (без секретов — только количество ключей). */
@Component
class StartupLog {

    private static final Logger log = LoggerFactory.getLogger(StartupLog.class);

    private final CheckInProperties properties;
    private final ObjectProvider<BuildProperties> build;

    StartupLog(CheckInProperties properties, ObjectProvider<BuildProperties> build) {
        this.properties = properties;
        this.build = build;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        BuildProperties info = build.getIfAvailable();
        log.info("Check-in Service {} started: event {}, card base {}, Registration {}, job TTL {}, "
                        + "session TTL {}, heartbeat {}, {} Station Agent API key(s)",
                info == null ? "(version unknown)" : info.getVersion(),
                properties.eventId(), properties.cardBaseUrl(), properties.registration().baseUrl(),
                properties.provisioningJobTtl(), properties.stationAgent().sessionTtl(),
                properties.stationAgent().heartbeatInterval(), properties.stationAgent().apiKeys().size());
    }
}
