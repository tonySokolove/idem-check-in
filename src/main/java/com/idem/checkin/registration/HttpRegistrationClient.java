package com.idem.checkin.registration;

import com.idem.checkin.CheckInProperties;
import com.idem.checkin.logging.Mdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
class HttpRegistrationClient implements RegistrationClient {

    private static final Logger log = LoggerFactory.getLogger(HttpRegistrationClient.class);

    private final RestClient http;

    HttpRegistrationClient(CheckInProperties properties) {
        var settings = properties.registration();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(settings.connectTimeout())
                .build());
        factory.setReadTimeout(settings.readTimeout());
        this.http = RestClient.builder()
                .baseUrl(settings.baseUrl().toString())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + settings.token())
                .requestInterceptor((request, body, execution) -> {
                    // Сквозной correlationId: по нему запрос находится и в логах Registration Service
                    String correlationId = MDC.get(Mdc.CORRELATION_ID);
                    if (correlationId != null) {
                        request.getHeaders().set(Mdc.CORRELATION_HEADER, correlationId);
                    }
                    long started = System.nanoTime();
                    // Только путь: в query лежит референс гостя
                    String call = request.getMethod() + " " + request.getURI().getPath();
                    try {
                        var response = execution.execute(request, body);
                        long millis = (System.nanoTime() - started) / 1_000_000;
                        if (response.getStatusCode().is5xxServerError()) {
                            log.warn("Registration API {} -> {} ({} ms)", call, response.getStatusCode().value(), millis);
                        } else {
                            log.debug("Registration API {} -> {} ({} ms)", call, response.getStatusCode().value(), millis);
                        }
                        return response;
                    } catch (IOException error) {
                        log.warn("Registration API {} failed after {} ms: {}", call,
                                (System.nanoTime() - started) / 1_000_000, error.toString());
                        throw error;
                    }
                })
                .build();
    }

    @Override
    public Optional<RegistrationInfo> lookup(UUID eventId, String externalRegistrationId) {
        try {
            return Optional.ofNullable(http.get()
                    .uri(b -> b.path("/internal/v1/registrations")
                            .queryParam("eventId", eventId)
                            .queryParam("externalRegistrationId", "{ref}")
                            .build(externalRegistrationId))
                    .retrieve()
                    .body(RegistrationInfo.class));
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            if (error.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new AmbiguousReferenceException();
            }
            throw unavailable(error);
        } catch (RestClientException error) {
            throw unavailable(error);
        }
    }

    @Override
    public Optional<RegistrationInfo> get(UUID registrationId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/v1/registrations/{id}", registrationId)
                    .retrieve().body(RegistrationInfo.class));
        } catch (HttpClientErrorException.NotFound error) {
            return Optional.empty();
        } catch (RestClientException error) {
            throw unavailable(error);
        }
    }

    @Override
    public void markCheckedIn(UUID registrationId, Instant checkedInAt) {
        try {
            http.post().uri("/internal/v1/registrations/{id}/check-in", registrationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("checkedInAt", checkedInAt.toString()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException error) {
            throw unavailable(error);
        }
    }

    private static RegistrationUnavailableException unavailable(Exception error) {
        return new RegistrationUnavailableException("Registration Service unavailable: " + error.getClass().getSimpleName(), error);
    }
}
