package com.prism.gateway.core;

/** A rejection or failure that is returned to the caller as an OpenAI-style error body. */
public class PrismException extends RuntimeException {

    private final PrismError error;
    private final Long retryAfterSeconds;

    public PrismException(PrismError error, String message) {
        this(error, message, null);
    }

    public PrismException(PrismError error, String message, Long retryAfterSeconds) {
        super(message);
        this.error = error;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public PrismError error() {
        return error;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
