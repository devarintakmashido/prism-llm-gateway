package com.prism.gateway.provider;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Receives an upstream response body line by line (from the JDK HttpClient) and hands the lines
 * to the thread relaying the stream. Two properties matter:
 * <ul>
 *   <li>Backpressure: we request one line at a time, so a slow client slows the upstream read
 *       instead of piling tokens up in gateway memory.</li>
 *   <li>Idle timeout: the reader waits at most {@code idle} for the next line, so a provider that
 *       goes silent mid-stream cannot hang the client.</li>
 * </ul>
 */
final class LineQueue implements Flow.Subscriber<String> {

    private static final Object END = new Object();

    private record Failure(Throwable cause) {
    }

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private volatile Flow.Subscription subscription;
    private volatile boolean cancelled;

    @Override
    public void onSubscribe(Flow.Subscription s) {
        subscription = s;
        if (cancelled) {
            s.cancel();
        } else {
            s.request(1);
        }
    }

    @Override
    public void onNext(String line) {
        queue.add(line);
    }

    @Override
    public void onError(Throwable t) {
        queue.add(new Failure(t));
    }

    @Override
    public void onComplete() {
        queue.add(END);
    }

    void fail(Throwable t) {
        queue.add(new Failure(t));
    }

    /** Next line without its line terminator, or null when the upstream finished the body. */
    String next(Duration idle) throws UpstreamException {
        Object item;
        try {
            item = queue.poll(idle.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw UpstreamException.of(UpstreamException.Kind.STREAM_BROKEN, "interrupted while reading stream", e);
        }
        if (item == null) {
            throw UpstreamException.of(UpstreamException.Kind.STREAM_IDLE_TIMEOUT,
                    "no data from provider for " + idle.toMillis() + " ms");
        }
        if (item == END) {
            queue.add(END); // stay at end-of-stream for any further reads
            return null;
        }
        if (item instanceof Failure f) {
            queue.add(f);
            throw UpstreamException.of(UpstreamException.Kind.STREAM_BROKEN,
                    "provider connection failed mid-stream: " + f.cause().getClass().getSimpleName(), f.cause());
        }
        Flow.Subscription s = subscription;
        if (s != null) {
            s.request(1);
        }
        String line = (String) item;
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** Collects a (small) error body, e.g. the JSON of a 503, up to maxChars. */
    String drain(Duration wait, int maxChars) {
        StringBuilder sb = new StringBuilder();
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            while (sb.length() < maxChars) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                String line = next(Duration.ofNanos(left));
                if (line == null) {
                    break;
                }
                sb.append(line).append('\n');
            }
        } catch (UpstreamException ignored) {
            // best effort: we only want the error message
        }
        return sb.toString();
    }

    void cancel() {
        cancelled = true;
        Flow.Subscription s = subscription;
        if (s != null) {
            s.cancel();
        }
    }
}
