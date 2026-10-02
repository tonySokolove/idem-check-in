package com.idem.checkin.session;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class StationSessionRepository {

    private final JdbcClient jdbc;

    public StationSessionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lockHub(String deviceId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "hub:" + deviceId)
                .query((rs, n) -> 1).list();
    }

    /** Закрывает прежнюю активную сессию этого Admin Hub (новый ноутбук или перезапуск Agent). */
    public void closeActiveForHub(String deviceId, Instant now) {
        jdbc.sql("""
                        UPDATE station_session
                        SET status = 'CLOSED', closed_at = :now,
                            close_reason = CASE WHEN expires_at <= :now THEN 'EXPIRED' ELSE 'REPLACED' END
                        WHERE admin_hub_device_id = :hub AND status = 'ACTIVE'
                        """)
                .param("hub", deviceId)
                .param("now", Timestamp.from(now))
                .update();
    }

    public void insert(StationSession session, byte[] tokenHash) {
        jdbc.sql("""
                        INSERT INTO station_session (id, event_id, admin_hub_device_id, admin_hub_firmware_version,
                            protocol_version, agent_version, token_hash, status, created_at, last_seen_at, expires_at)
                        VALUES (:id, :event, :hub, :firmware, :protocol, :agent, :hash, 'ACTIVE', :now, :now, :expires)
                        """)
                .param("id", session.id())
                .param("event", session.eventId())
                .param("hub", session.adminHubDeviceId())
                .param("firmware", session.adminHubFirmwareVersion())
                .param("protocol", session.protocolVersion())
                .param("agent", session.agentVersion())
                .param("hash", tokenHash)
                .param("now", Timestamp.from(session.createdAt()))
                .param("expires", Timestamp.from(session.expiresAt()))
                .update();
    }

    public Optional<StationSession> findByTokenHash(byte[] tokenHash) {
        return jdbc.sql("SELECT * FROM station_session WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .query(StationSessionRepository::map)
                .optional();
    }

    public void touch(UUID id, Instant now, Instant expiresAt) {
        jdbc.sql("UPDATE station_session SET last_seen_at = :now, expires_at = :expires WHERE id = :id AND status = 'ACTIVE'")
                .param("id", id)
                .param("now", Timestamp.from(now))
                .param("expires", Timestamp.from(expiresAt))
                .update();
    }

    public void close(UUID id, String reason, Instant now) {
        jdbc.sql("""
                        UPDATE station_session SET status = 'CLOSED', close_reason = :reason, closed_at = :now
                        WHERE id = :id AND status = 'ACTIVE'
                        """)
                .param("id", id)
                .param("reason", reason)
                .param("now", Timestamp.from(now))
                .update();
    }

    private static StationSession map(ResultSet rs, int row) throws SQLException {
        return new StationSession(
                rs.getObject("id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("admin_hub_device_id"),
                rs.getString("admin_hub_firmware_version"),
                (Integer) rs.getObject("protocol_version"),
                rs.getString("agent_version"),
                rs.getString("status"),
                rs.getString("close_reason"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("last_seen_at")),
                instant(rs.getTimestamp("expires_at")),
                instant(rs.getTimestamp("closed_at")));
    }

    static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
