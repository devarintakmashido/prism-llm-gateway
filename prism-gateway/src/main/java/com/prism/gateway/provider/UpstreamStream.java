package com.prism.gateway.provider;

import java.time.Duration;

/**
 * An open server-sent-events stream from a provider. {@link #nextData()} yields the payload of each
 * "data:" line as it arrives (a JSON chunk, or "[DONE]"), skipping blank separators and ":"
 * comment lines such as OpenRouter's keep-alives.
 */
public final class UpstreamStream implements AutoCloseable {

    private final LineQueue lines;
    private final Duration idleTimeout;
    private final long deadlineNanos;
    private final Runnable abort;
    private String pending;

    UpstreamStream(LineQueue lines, Duration idleTimeout, Duration maxDuration, Runnable abort) {
        this.lines = lines;
        this.idleTimeout = idleTimeout;
        this.deadlineNanos = System.nanoTime() + maxDuration.toNanos();
        this.abort = abort;
    }

    /** Reads ahead to the first event, so a stream that dies before its first token can still fail over. */
    void awaitFirstEvent(Duration timeout) throws UpstreamException {
        String first = next(timeout);
        if (first == null) {
            throw UpstreamException.of(UpstreamException.Kind.STREAM_BROKEN, "provider closed the stream before the first event");
        }
        pending = first;
    }

    /** The next "data:" payload, or null when the provider closed the stream. */
    public String nextData() throws UpstreamException {
        if (pending != null) {
            String p = pending;
            pending = null;
            return p;
        }
        return next(idleTimeout);
    }

    private String next(Duration idle) throws UpstreamException {
        while (true) {
            if (System.nanoTime() > deadlineNanos) {
                // Keep-alive comments reset the idle timer, so this is what stops an endless stream.
                throw UpstreamException.of(UpstreamException.Kind.TOTAL_TIMEOUT, "stream exceeded its maximum duration");
            }
            String line = lines.next(idle);
            if (line == null) {
                return null;
            }
            if (line.isEmpty() || line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                return line.substring(5).strip();
            }
            // "event:", "id:" and "retry:" fields carry nothing we need.
        }
    }

    /** Stops reading and closes the upstream connection, e.g. when our client disconnected. */
    @Override
    public void close() {
        lines.cancel();
        abort.run();
    }
}
