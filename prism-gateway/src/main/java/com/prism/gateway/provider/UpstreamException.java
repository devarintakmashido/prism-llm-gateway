package com.prism.gateway.provider;

/**
 * A failed upstream call, classified so the failover logic can decide what to do next:
 * retry the same provider, move on to the next one, or give up and tell the caller.
 */
public class UpstreamException extends Exception {

    public enum Kind {
        /** Could not open a connection (provider process dead, DNS, refused). */
        CONNECT_FAILED,
        /** No response headers within the first-byte timeout (slow or hung provider). */
        FIRST_BYTE_TIMEOUT,
        /** The whole call exceeded its time budget. */
        TOTAL_TIMEOUT,
        /** Provider answered with a non-2xx status. */
        HTTP_ERROR,
        /** 2xx, but the body was not a valid chat completion. */
        MALFORMED,
        /** Connection dropped while reading. */
        NETWORK,
        /** A stream went silent for longer than the idle timeout. */
        STREAM_IDLE_TIMEOUT,
        /** A stream broke after it had started. */
        STREAM_BROKEN
    }

    private final Kind kind;
    private final int status;
    private final Long retryAfterMillis;

    public UpstreamException(Kind kind, int status, String message, Long retryAfterMillis, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.retryAfterMillis = retryAfterMillis;
    }

    public static UpstreamException of(Kind kind, String message) {
        return new UpstreamException(kind, 0, message, null, null);
    }

    public static UpstreamException of(Kind kind, String message, Throwable cause) {
        return new UpstreamException(kind, 0, message, null, cause);
    }

    public static UpstreamException http(int status, String message, Long retryAfterMillis) {
        return new UpstreamException(Kind.HTTP_ERROR, status, message, retryAfterMillis, null);
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public Long retryAfterMillis() {
        return retryAfterMillis;
    }

    /**
     * Transient blips are worth another try on the same provider: 500/502/504, a dropped connection,
     * or a garbled body. A provider that is down (503), rate-limiting us (429), unreachable, or too
     * slow is not; we fail over to the next provider straight away instead of waiting on it.
     */
    public boolean retryOnSameProvider() {
        return switch (kind) {
            case NETWORK, MALFORMED -> true;
            case HTTP_ERROR -> status == 500 || status == 502 || status == 504 || status == 408;
            default -> false;
        };
    }

    /**
     * The provider rejected the request itself (400/422). Another provider would reject it too, so
     * this goes back to the caller as their error instead of triggering failover.
     */
    public boolean isCallerError() {
        return kind == Kind.HTTP_ERROR && (status == 400 || status == 422);
    }

    public boolean isTimeout() {
        return kind == Kind.FIRST_BYTE_TIMEOUT || kind == Kind.TOTAL_TIMEOUT || kind == Kind.STREAM_IDLE_TIMEOUT;
    }

    /** Short label for request logs and health stats, e.g. "http_503" or "first_byte_timeout". */
    public String label() {
        return kind == Kind.HTTP_ERROR ? "http_" + status : kind.name().toLowerCase();
    }
}
