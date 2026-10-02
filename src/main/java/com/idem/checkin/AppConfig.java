package com.idem.checkin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class AppConfig {

    /** Микросекунды — та же точность, что у timestamptz в PostgreSQL: время в ответах не «скачет». */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
