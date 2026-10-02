-- Данные Check-in Service: сессии стоек регистрации, бейджи и задания на запись NFC.

CREATE TABLE station_session (
    id                         uuid PRIMARY KEY,
    event_id                   uuid        NOT NULL,
    admin_hub_device_id        text        NOT NULL,
    admin_hub_firmware_version text        NULL,
    protocol_version           integer     NULL,
    agent_version              text        NULL,
    -- SHA-256 от sessionToken; сам токен не хранится
    token_hash                 bytea       NOT NULL UNIQUE,
    status                     text        NOT NULL CHECK (status IN ('ACTIVE', 'CLOSED')),
    close_reason               text        NULL CHECK (close_reason IN ('CLIENT_CLOSED', 'REPLACED', 'EXPIRED')),
    created_at                 timestamptz NOT NULL,
    last_seen_at               timestamptz NOT NULL,
    expires_at                 timestamptz NOT NULL,
    closed_at                  timestamptz NULL
);

-- Один Admin Hub — одна активная сессия: новая сессия закрывает предыдущую
CREATE UNIQUE INDEX station_session_one_active_per_hub
    ON station_session (admin_hub_device_id) WHERE status = 'ACTIVE';

-- ВРЕМЕННО в Check-in Service: бейдж и public token до решения «Badge ownership»
-- (предложенный владелец — Participant & Profile Service).
CREATE TABLE badge (
    id              uuid PRIMARY KEY,
    registration_id uuid        NOT NULL,
    event_id        uuid        NOT NULL,
    public_token    text        NOT NULL UNIQUE,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'DISCARDED', 'REVOKED')),
    created_at      timestamptz NOT NULL,
    activated_at    timestamptz NULL,
    closed_at       timestamptz NULL
);

CREATE UNIQUE INDEX badge_one_active_per_registration
    ON badge (registration_id) WHERE status = 'ACTIVE';

CREATE TABLE provisioning_job (
    id                 uuid PRIMARY KEY,
    station_session_id uuid        NOT NULL REFERENCES station_session (id),
    registration_id    uuid        NOT NULL,
    badge_id           uuid        NOT NULL REFERENCES badge (id),
    payload_type       text        NOT NULL CHECK (payload_type IN ('NDEF_URI')),
    payload            text        NOT NULL,
    status             text        NOT NULL CHECK (status IN ('DELIVERED', 'ACCEPTED', 'WAITING_FOR_TAG', 'WRITING',
                                                              'VERIFYING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')),
    error_code         text        NULL,
    error_message      text        NULL,
    correlation_id     text        NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    -- До этого момента Admin Hub должен начать запись; после — job считается TIMED_OUT
    expires_at         timestamptz NOT NULL,
    completed_at       timestamptz NULL,
    -- Отмечен ли check-in в Registration Service (повторяется фоново при сбое)
    registration_synced boolean    NOT NULL DEFAULT false
);

-- Не больше одного незавершённого job на регистрацию и на стойку
CREATE UNIQUE INDEX provisioning_job_one_active_per_registration
    ON provisioning_job (registration_id)
    WHERE status IN ('DELIVERED', 'ACCEPTED', 'WAITING_FOR_TAG', 'WRITING', 'VERIFYING');
CREATE UNIQUE INDEX provisioning_job_one_active_per_session
    ON provisioning_job (station_session_id)
    WHERE status IN ('DELIVERED', 'ACCEPTED', 'WAITING_FOR_TAG', 'WRITING', 'VERIFYING');
CREATE INDEX provisioning_job_unsynced
    ON provisioning_job (completed_at) WHERE status = 'COMPLETED' AND NOT registration_synced;
