package com.idem.checkin.logging;

/** Ключи MDC, которые выводятся в каждой строке лога (см. log4j2-spring.xml). */
public final class Mdc {

    /** Сквозной id запроса; приходит и уходит в заголовке {@link #CORRELATION_HEADER}. */
    public static final String CORRELATION_ID = "correlationId";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    /** Station Session, от имени которой выполняется запрос. */
    public static final String SESSION_ID = "sessionId";
    /** Задание на запись бейджа. */
    public static final String JOB_ID = "jobId";

    private Mdc() {
    }
}
