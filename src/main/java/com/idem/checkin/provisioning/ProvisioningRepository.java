package com.idem.checkin.provisioning;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProvisioningRepository {

    /** Сколько запись/проверка может «висеть» без новых статусов, прежде чем job будет TIMED_OUT. */
    static final Duration STUCK_WRITE_TIMEOUT = Duration.ofMinutes(5);

    private static final String ACTIVE = "('DELIVERED', 'ACCEPTED', 'WAITING_FOR_TAG', 'WRITING', 'VERIFYING')";

    private static final String SELECT = """
            SELECT j.*, b.status AS badge_status
            FROM provisioning_job j JOIN badge b ON b.id = j.badge_id
            """;

    private final JdbcClient jdbc;

    public ProvisioningRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(String key) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", key)
                .query((rs, n) -> 1).list();
    }

    /**
     * Переводит в TIMED_OUT задания, которые не начали запись вовремя или зависли,
     * и освобождает их PENDING-бейджи.
     */
    public int expireStale(UUID registrationId, UUID sessionId, Instant now) {
        return jdbc.sql("""
                        WITH expired AS (
                            UPDATE provisioning_job
                            SET status = 'TIMED_OUT', error_code = 'TIMED_OUT', updated_at = :now
                            WHERE (registration_id = :registration OR station_session_id = :session)
                              AND ((status IN ('DELIVERED', 'ACCEPTED', 'WAITING_FOR_TAG') AND expires_at <= :now)
                                OR (status IN ('WRITING', 'VERIFYING') AND updated_at <= :stuck))
                            RETURNING badge_id)
                        UPDATE badge SET status = 'DISCARDED', closed_at = :now
                        WHERE id IN (SELECT badge_id FROM expired) AND status = 'PENDING'
                        """)
                .param("registration", registrationId)
                .param("session", sessionId)
                .param("now", Timestamp.from(now))
                .param("stuck", Timestamp.from(now.minus(STUCK_WRITE_TIMEOUT)))
                .update();
    }

    public int expireJob(UUID jobId, Instant now) {
        return jdbc.sql("""
                        WITH expired AS (
                            UPDATE provisioning_job
                            SET status = 'TIMED_OUT', error_code = 'TIMED_OUT', updated_at = :now
                            WHERE id = :job
                              AND ((status IN ('DELIVERED', 'ACCEPTED', 'WAITING_FOR_TAG') AND expires_at <= :now)
                                OR (status IN ('WRITING', 'VERIFYING') AND updated_at <= :stuck))
                            RETURNING badge_id)
                        UPDATE badge SET status = 'DISCARDED', closed_at = :now
                        WHERE id IN (SELECT badge_id FROM expired) AND status = 'PENDING'
                        """)
                .param("job", jobId)
                .param("now", Timestamp.from(now))
                .param("stuck", Timestamp.from(now.minus(STUCK_WRITE_TIMEOUT)))
                .update();
    }

    public boolean hasActiveBadge(UUID registrationId) {
        return jdbc.sql("SELECT count(*) FROM badge WHERE registration_id = :r AND status = 'ACTIVE'")
                .param("r", registrationId)
                .query(Long.class).single() > 0;
    }

    public Optional<ProvisioningJob> findActiveByRegistration(UUID registrationId) {
        return jdbc.sql(SELECT + " WHERE j.registration_id = :r AND j.status IN " + ACTIVE)
                .param("r", registrationId)
                .query(ProvisioningRepository::map).optional();
    }

    public Optional<ProvisioningJob> findActiveBySession(UUID sessionId) {
        return jdbc.sql(SELECT + " WHERE j.station_session_id = :s AND j.status IN " + ACTIVE)
                .param("s", sessionId)
                .query(ProvisioningRepository::map).optional();
    }

    public Optional<ProvisioningJob> find(UUID jobId) {
        return jdbc.sql(SELECT + " WHERE j.id = :id")
                .param("id", jobId)
                .query(ProvisioningRepository::map).optional();
    }

    public void insertBadge(UUID id, UUID registrationId, UUID eventId, String token, Instant now) {
        jdbc.sql("""
                        INSERT INTO badge (id, registration_id, event_id, public_token, status, created_at)
                        VALUES (:id, :registration, :event, :token, 'PENDING', :now)
                        """)
                .param("id", id)
                .param("registration", registrationId)
                .param("event", eventId)
                .param("token", token)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void insertJob(UUID id, UUID sessionId, UUID registrationId, UUID badgeId, String payload,
                          String correlationId, Instant now, Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO provisioning_job (id, station_session_id, registration_id, badge_id, payload_type,
                            payload, status, correlation_id, created_at, updated_at, expires_at)
                        VALUES (:id, :session, :registration, :badge, 'NDEF_URI', :payload, 'DELIVERED',
                                :correlation, :now, :now, :expires)
                        """)
                .param("id", id)
                .param("session", sessionId)
                .param("registration", registrationId)
                .param("badge", badgeId)
                .param("payload", payload)
                .param("correlation", correlationId)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expiresAt))
                .update();
    }

    public void updateStatus(UUID jobId, JobStatus status, String errorCode, String errorMessage, Instant now) {
        jdbc.sql("""
                        UPDATE provisioning_job
                        SET status = :status, error_code = :code, error_message = :message, updated_at = :now,
                            completed_at = CASE WHEN :status = 'COMPLETED' THEN CAST(:now AS timestamptz) ELSE completed_at END
                        WHERE id = :id
                        """)
                .param("id", jobId)
                .param("status", status.name())
                .param("code", errorCode)
                .param("message", errorMessage)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void setBadgeStatus(UUID badgeId, String status, Instant now) {
        jdbc.sql("""
                        UPDATE badge
                        SET status = :status,
                            activated_at = CASE WHEN :status = 'ACTIVE' THEN CAST(:now AS timestamptz) ELSE activated_at END,
                            closed_at = CASE WHEN :status IN ('DISCARDED', 'REVOKED') THEN CAST(:now AS timestamptz) ELSE NULL END
                        WHERE id = :id
                        """)
                .param("id", badgeId)
                .param("status", status)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void markRegistrationSynced(UUID jobId) {
        jdbc.sql("UPDATE provisioning_job SET registration_synced = true WHERE id = :id").param("id", jobId).update();
    }

    public List<ProvisioningJob> findUnsynced(int limit) {
        return jdbc.sql(SELECT + " WHERE j.status = 'COMPLETED' AND NOT j.registration_synced ORDER BY j.completed_at LIMIT :limit")
                .param("limit", limit)
                .query(ProvisioningRepository::map).list();
    }

    /** Активный бейдж по public token — для страницы визитки. */
    public Optional<UUID> findRegistrationByActiveToken(String token) {
        return jdbc.sql("SELECT registration_id FROM badge WHERE public_token = :t AND status = 'ACTIVE'")
                .param("t", token)
                .query((rs, n) -> rs.getObject(1, UUID.class)).optional();
    }

    private static ProvisioningJob map(ResultSet rs, int row) throws SQLException {
        return new ProvisioningJob(
                rs.getObject("id", UUID.class),
                rs.getObject("station_session_id", UUID.class),
                rs.getObject("registration_id", UUID.class),
                rs.getObject("badge_id", UUID.class),
                rs.getString("payload_type"),
                rs.getString("payload"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("completed_at")),
                rs.getBoolean("registration_synced"),
                rs.getString("badge_status"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
