package com.idem.checkin;

import com.idem.checkin.provisioning.ProvisioningJob;
import com.idem.checkin.provisioning.ProvisioningRepository;
import com.idem.checkin.provisioning.RegistrationSync;
import com.idem.checkin.registration.RegistrationClient;
import com.idem.checkin.registration.RegistrationInfo;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "checkin.event-id=" + StationAgentFlowTest.EVENT,
        "checkin.card-base-url=https://idem-check-in.example/c/",
        "checkin.station-agent.api-keys=" + StationAgentFlowTest.API_KEY,
        "checkin.registration.base-url=http://registration.invalid",
        "checkin.registration.token=registration-token-0123456789abcdef0123",
        "checkin.registration-sync-interval=PT1H"
})
@AutoConfigureMockMvc
class StationAgentFlowTest extends EmbeddedPostgresSupport {

    static final String EVENT = "e23bbab1-f226-46ca-b558-d253f24418bc";
    static final String API_KEY = "station-agent-key-0123456789abcdef0123";

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    RegistrationSync sync;
    @Autowired
    ProvisioningRepository repository;
    @MockitoBean
    RegistrationClient registrations;

    @BeforeEach
    void resetMocks() {
        reset(registrations);
    }

    // ---------- helpers ----------

    private record Session(String id, String token) {
    }

    private Session openSession(String hubId) throws Exception {
        String body = mvc.perform(post("/api/v1/station-sessions")
                        .header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"adminHub":{"deviceId":"%s","firmwareVersion":"0.4.1","protocolVersion":1},
                                 "agent":{"version":"0.1.0"}}
                                """.formatted(hubId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(EVENT))
                .andExpect(jsonPath("$.heartbeatIntervalSeconds").value(60))
                .andReturn().getResponse().getContentAsString();
        return new Session(JsonPath.read(body, "$.sessionId"), JsonPath.read(body, "$.sessionToken"));
    }

    private ResultActions checkIn(Session session, String reference) throws Exception {
        return mvc.perform(post("/api/v1/check-ins")
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"externalRegistrationId\":\"" + reference + "\"}"));
    }

    private ResultActions report(Session session, String jobId, String status, String readBack) throws Exception {
        String extra = readBack == null ? "" : ",\"readBackPayload\":\"" + readBack + "\"";
        return mvc.perform(post("/api/v1/provisioning/{id}/status", jobId)
                .header("Authorization", "Bearer " + session.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"" + extra + "}"));
    }

    private RegistrationInfo registration(String mode, String name, String status, String ticket, boolean valid) {
        var info = new RegistrationInfo(UUID.randomUUID(), UUID.fromString(EVENT), mode, status, ticket, valid, name,
                Instant.parse("2026-09-01T00:00:00Z"), null);
        when(registrations.get(info.registrationId())).thenReturn(Optional.of(info));
        return info;
    }

    private void known(String reference, RegistrationInfo info) {
        when(registrations.lookup(UUID.fromString(EVENT), reference)).thenReturn(Optional.of(info));
    }

    private static String hub() {
        return "AH-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---------- tests ----------

    @Test
    void fullFlowFromQrToActiveBadge() throws Exception {
        var guest = registration("FULL", "Guest 001", "ACTIVE", "VALID", true);
        known("R-100", guest);
        Session session = openSession(hub());

        String body = checkIn(session, "R-100")
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.participant.visibility").value("VISIBLE"))
                .andExpect(jsonPath("$.participant.displayName").value("Guest 001"))
                .andExpect(jsonPath("$.provisioning.payloadType").value("NDEF_URI"))
                .andExpect(jsonPath("$.provisioning.payload").value(startsWith("https://idem-check-in.example/c/")))
                .andReturn().getResponse().getContentAsString();
        String jobId = JsonPath.read(body, "$.provisioning.jobId");
        String payload = JsonPath.read(body, "$.provisioning.payload");

        for (String step : new String[]{"ACCEPTED", "WAITING_FOR_TAG", "WRITING", "VERIFYING"}) {
            report(session, jobId, step, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(step));
        }
        // Статус «из прошлого» не откатывает job назад
        report(session, jobId, "ACCEPTED", null).andExpect(jsonPath("$.status").value("VERIFYING"));

        report(session, jobId, "COMPLETED", payload)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.badgeStatus").value("ACTIVE"));
        // Повтор того же результата — идемпотентно
        report(session, jobId, "COMPLETED", payload).andExpect(status().isOk());
        verify(registrations, times(1)).markCheckedIn(eq(guest.registrationId()), any());

        String token = payload.substring(payload.lastIndexOf('/') + 1);
        mvc.perform(get("/c/{token}", token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Guest 001")));

        checkIn(session, "R-100").andExpect(jsonPath("$.status").value("BADGE_ALREADY_ISSUED"));
    }

    @Test
    void reportsBusinessOutcomesWithoutProvisioning() throws Exception {
        Session session = openSession(hub());
        when(registrations.lookup(any(), eq("R-missing"))).thenReturn(Optional.empty());
        checkIn(session, "R-missing")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.provisioning").doesNotExist());

        known("R-refunded", registration("FULL", "Guest", "ACTIVE", "REFUNDED", false));
        checkIn(session, "R-refunded")
                .andExpect(jsonPath("$.status").value("INVALID_REGISTRATION"))
                .andExpect(jsonPath("$.reason").value("TICKET_REFUNDED"));

        known("R-cancelled", registration("FULL", "Guest", "CANCELLED", "VALID", true));
        checkIn(session, "R-cancelled").andExpect(jsonPath("$.reason").value("REGISTRATION_CANCELLED"));

        when(registrations.lookup(any(), eq("R-twice"))).thenThrow(new RegistrationClient.AmbiguousReferenceException());
        checkIn(session, "R-twice").andExpect(jsonPath("$.reason").value("AMBIGUOUS_REFERENCE"));

        when(registrations.lookup(any(), eq("R-down")))
                .thenThrow(new RegistrationClient.RegistrationUnavailableException("down", null));
        checkIn(session, "R-down")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("REGISTRATION_UNAVAILABLE"));
    }

    @Test
    void pseudonymousGuestIsAnonymousForHostAndCard() throws Exception {
        known("R-anon", registration("PSEUDONYMOUS", null, "ACTIVE", "VALID", true));
        Session session = openSession(hub());
        String body = checkIn(session, "R-anon")
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.participant.visibility").value("ANONYMOUS"))
                .andExpect(jsonPath("$.participant.displayName").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String payload = JsonPath.read(body, "$.provisioning.payload");
        report(session, JsonPath.read(body, "$.provisioning.jobId"), "COMPLETED", payload).andExpect(status().isOk());
        mvc.perform(get("/c/{token}", payload.substring(payload.lastIndexOf('/') + 1)))
                .andExpect(content().string(containsString("Гость мероприятия")));
    }

    @Test
    void repeatedScanReturnsSameJobAndStationTakesOneJobAtATime() throws Exception {
        known("R-200", registration("FULL", "Guest 200", "ACTIVE", "VALID", true));
        known("R-201", registration("FULL", "Guest 201", "ACTIVE", "VALID", true));
        Session session = openSession(hub());

        String jobId = JsonPath.read(checkIn(session, "R-200").andReturn().getResponse().getContentAsString(),
                "$.provisioning.jobId");
        checkIn(session, "R-200")
                .andExpect(jsonPath("$.status").value("PROVISIONING_IN_PROGRESS"))
                .andExpect(jsonPath("$.provisioning.jobId").value(jobId));
        checkIn(session, "R-201")
                .andExpect(jsonPath("$.status").value("STATION_BUSY"))
                .andExpect(jsonPath("$.activeJobId").value(jobId));

        // Другая стойка не получает чужую команду записи
        checkIn(openSession(hub()), "R-200")
                .andExpect(jsonPath("$.status").value("PROVISIONING_IN_PROGRESS"))
                .andExpect(jsonPath("$.provisioning").doesNotExist());

        report(session, jobId, "FAILED", null).andExpect(jsonPath("$.status").value("FAILED"));
        checkIn(session, "R-201").andExpect(jsonPath("$.status").value("VALID"));
    }

    @Test
    void expiredJobCannotStartWritingButLateVerifiedWriteStillCounts() throws Exception {
        known("R-300", registration("FULL", "Guest 300", "ACTIVE", "VALID", true));
        Session session = openSession(hub());
        String first = checkIn(session, "R-300").andReturn().getResponse().getContentAsString();
        String firstJob = JsonPath.read(first, "$.provisioning.jobId");
        report(session, firstJob, "WAITING_FOR_TAG", null).andExpect(status().isOk());

        clock.advance(Duration.ofMinutes(3));
        report(session, firstJob, "WRITING", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("JOB_TIMED_OUT"))
                .andExpect(jsonPath("$.details.job.status").value("TIMED_OUT"));

        // Повторный скан после таймаута выдаёт новый job
        String second = checkIn(session, "R-300")
                .andExpect(jsonPath("$.status").value("VALID"))
                .andReturn().getResponse().getContentAsString();
        String secondJob = JsonPath.read(second, "$.provisioning.jobId");

        // Hub всё-таки записал первую метку и подтвердил чтением — она активируется
        report(session, firstJob, "COMPLETED", JsonPath.read(first, "$.provisioning.payload"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.badgeStatus").value("ACTIVE"));
        // Второй бейдж тому же гостю уже не нужен
        report(session, secondJob, "COMPLETED", JsonPath.read(second, "$.provisioning.payload"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error.code").value("BADGE_ALREADY_ISSUED"));
    }

    @Test
    void completionRequiresMatchingReadBack() throws Exception {
        known("R-400", registration("FULL", "Guest 400", "ACTIVE", "VALID", true));
        Session session = openSession(hub());
        String jobId = JsonPath.read(checkIn(session, "R-400").andReturn().getResponse().getContentAsString(),
                "$.provisioning.jobId");
        report(session, jobId, "COMPLETED", null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error").value("VERIFICATION_MISMATCH"));
        report(session, jobId, "COMPLETED", "https://evil.example/c/x").andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/v1/provisioning/{id}", jobId).header("Authorization", "Bearer " + session.token()))
                .andExpect(jsonPath("$.status").value("DELIVERED"));
        report(session, jobId, "TIMED_OUT", null).andExpect(status().isBadRequest());
    }

    @Test
    void sessionLifecycleAndAuthentication() throws Exception {
        mvc.perform(post("/api/v1/station-sessions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminHub\":{\"deviceId\":\"AH-1\"}}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/station-sessions").header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"adminHub\":{\"deviceId\":\"bad id!\"}}"))
                .andExpect(status().isBadRequest());
        checkIn(new Session("x", "not-a-token"), "R-1").andExpect(status().isUnauthorized());

        String hubId = hub();
        Session first = openSession(hubId);
        clock.advance(Duration.ofMinutes(4));
        mvc.perform(post("/api/v1/station-sessions/{id}/heartbeat", first.id())
                        .header("Authorization", "Bearer " + first.token()))
                .andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(4));
        // Благодаря heartbeat сессия жива
        when(registrations.lookup(any(), any())).thenReturn(Optional.empty());
        checkIn(first, "R-1").andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(6));
        checkIn(first, "R-1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("STATION_SESSION_EXPIRED"));

        Session second = openSession(hubId);
        Session other = openSession(hub());
        mvc.perform(post("/api/v1/station-sessions/{id}/heartbeat", second.id())
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/station-sessions/{id}", second.id()).header("Authorization", "Bearer " + second.token()))
                .andExpect(status().isNoContent());
        checkIn(second, "R-1").andExpect(jsonPath("$.error").value("STATION_SESSION_CLOSED"));
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void newSessionForSameHubClosesOldOneButOldJobCanStillFinish() throws Exception {
        known("R-500", registration("FULL", "Guest 500", "ACTIVE", "VALID", true));
        String hubId = hub();
        Session old = openSession(hubId);
        String body = checkIn(old, "R-500").andReturn().getResponse().getContentAsString();

        Session fresh = openSession(hubId);
        checkIn(old, "R-500").andExpect(jsonPath("$.error").value("STATION_SESSION_CLOSED"));
        // Чужая сессия не видит job
        report(fresh, JsonPath.read(body, "$.provisioning.jobId"), "WRITING", null).andExpect(status().isNotFound());
        report(old, JsonPath.read(body, "$.provisioning.jobId"), "COMPLETED", JsonPath.read(body, "$.provisioning.payload"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void checkInMarkIsRetriedWhenRegistrationIsDown() throws Exception {
        var guest = registration("FULL", "Guest 600", "ACTIVE", "VALID", true);
        known("R-600", guest);
        doThrow(new RegistrationClient.RegistrationUnavailableException("down", null))
                .when(registrations).markCheckedIn(eq(guest.registrationId()), any());
        Session session = openSession(hub());
        String body = checkIn(session, "R-600").andReturn().getResponse().getContentAsString();
        report(session, JsonPath.read(body, "$.provisioning.jobId"), "COMPLETED", JsonPath.read(body, "$.provisioning.payload"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        reset(registrations);
        sync.trySync(findUnsyncedFor(guest));
        verify(registrations).markCheckedIn(eq(guest.registrationId()), any());
    }

    private ProvisioningJob findUnsyncedFor(RegistrationInfo guest) {
        return repository.findUnsynced(100).stream()
                .filter(job -> job.registrationId().equals(guest.registrationId()))
                .findFirst().orElseThrow();
    }

    @Test
    void cardPageDoesNotLeakInactiveOrMalformedTokens() throws Exception {
        mvc.perform(get("/c/{token}", "AAAAAAAAAAAAAAAAAAAAAA")).andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("Guest"))));
        mvc.perform(get("/c/{token}", "<script>")).andExpect(status().isNotFound());
    }
}
