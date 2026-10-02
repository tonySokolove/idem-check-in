package com.idem.checkin.provisioning;

import java.util.EnumSet;
import java.util.Set;

/** Состояние задания на запись NFC. Check-in Service — source of truth. */
public enum JobStatus {
    /** Команда отдана Agent в ответе на check-in. */
    DELIVERED(0),
    /** Admin Hub принял команду. */
    ACCEPTED(1),
    /** Hub ждёт, когда положат метку. */
    WAITING_FOR_TAG(2),
    /** Идёт запись. */
    WRITING(3),
    /** Идёт read-after-write проверка. */
    VERIFYING(4),
    COMPLETED(-1),
    FAILED(-1),
    CANCELLED(-1),
    /** Hub не начал запись вовремя или запись зависла. Ставит только сервер. */
    TIMED_OUT(-1);

    /** Что может прислать Agent. */
    public static final Set<JobStatus> REPORTABLE = EnumSet.of(ACCEPTED, WAITING_FOR_TAG, WRITING, VERIFYING,
            COMPLETED, FAILED, CANCELLED);
    /** До начала записи — истекают по expiresAt. */
    public static final Set<JobStatus> BEFORE_WRITE = EnumSet.of(DELIVERED, ACCEPTED, WAITING_FOR_TAG);

    private final int progress;

    JobStatus(int progress) {
        this.progress = progress;
    }

    public boolean terminal() {
        return progress < 0;
    }

    public int progress() {
        return progress;
    }
}
