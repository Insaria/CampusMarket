package edu.bistu.campusmarket.exception;

import org.springframework.http.HttpStatus;

public class BusinessException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public BusinessException(HttpStatus status, String code, String publicMessage) {
        super(publicMessage);
        if (!status.is4xxClientError() && status != HttpStatus.SERVICE_UNAVAILABLE) {
            throw new IllegalArgumentException("Business exceptions must use a 4xx status or 503");
        }
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
