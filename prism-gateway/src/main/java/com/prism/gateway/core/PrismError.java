package com.prism.gateway.core;

/**
 * Every way a data-plane request can be rejected or fail. Each case has its own HTTP status,
 * OpenAI-style error "type", and request-log status, so callers can tell them apart from the body.
 */
public enum PrismError {
    INVALID_REQUEST(400, "invalid_request_error", "rejected_invalid"),
    AUTHENTICATION(401, "authentication_error", "rejected_auth"),
    BUDGET_EXCEEDED(402, "budget_exceeded", "rejected_budget"),
    MODEL_NOT_ALLOWED(403, "model_not_allowed", "rejected_allowlist"),
    MODEL_NOT_FOUND(404, "not_found_error", "rejected_unknown_model"),
    RATE_LIMITED(429, "rate_limit_exceeded", "rejected_rate_limit"),
    INTERNAL(500, "internal_error", "internal_error"),
    UPSTREAM_ERROR(502, "upstream_error", "upstream_error"),
    UPSTREAM_TIMEOUT(504, "upstream_timeout", "upstream_timeout");

    private final int httpStatus;
    private final String type;
    private final String logStatus;

    PrismError(int httpStatus, String type, String logStatus) {
        this.httpStatus = httpStatus;
        this.type = type;
        this.logStatus = logStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String type() {
        return type;
    }

    public String logStatus() {
        return logStatus;
    }
}
