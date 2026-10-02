package com.idem.checkin.api;

import org.springframework.http.HttpStatus;

/** Ошибка протокола с машиночитаемым кодом. Бизнес-результаты check-in ошибками не являются. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
