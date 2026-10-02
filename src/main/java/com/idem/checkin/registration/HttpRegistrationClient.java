package com.idem.checkin.registration;

import com.idem.checkin.CheckInProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
class HttpRegistrationClient implements RegistrationClient {

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
