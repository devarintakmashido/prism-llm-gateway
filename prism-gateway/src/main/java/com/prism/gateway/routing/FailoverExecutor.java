package com.prism.gateway.routing;

import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.GatewayConfig.ModelRoute;
import com.prism.gateway.config.GatewayConfig.Provider;
import com.prism.gateway.provider.UpstreamException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Walks an alias's fallback chain until one provider serves the request.
 *
 * <ol>
 *   <li>Order the chain by provider health, so a provider known to be down is not tried first.</li>
 *   <li>On a transient error (500/502/504, dropped connection, garbled body), retry the same provider
 *       with exponential backoff plus jitter, up to retry.max_attempts.</li>
 *   <li>On anything that says "this provider can't serve you right now" (503, 429, connect failure,
 *       timeout), move to the next provider immediately.</li>
 *   <li>If the provider rejects the request itself (400), stop: every provider would.</li>
 * </ol>
 * Every attempt is recorded for the request log, and a request served by anything other than the
 * configured primary is marked as a fallback.
 */
@Component
public class FailoverExecutor {

    public record Attempt(String provider, String model, int attempt, String outcome, long latencyMs) {
    }

    public record Served<T>(T value, ModelRoute route, boolean fallback, List<Attempt> attempts) {
        public int retries() {
            return Math.max(0, attempts.size() - 1);
        }
    }

    /** Every provider in the chain failed (or the caller's request was rejected upstream). */
    public static final class AllFailed extends Exception {
        private final UpstreamException last;
        private final List<Attempt> attempts;

        AllFailed(UpstreamException last, List<Attempt> attempts) {
            super(last == null ? "no provider available" : last.getMessage(), last);
            this.last = last;
            this.attempts = List.copyOf(attempts);
        }

        public UpstreamException last() {
            return last;
        }

        public List<Attempt> attempts() {
            return attempts;
        }
    }

    @FunctionalInterface
    public interface Call<T> {
        T apply(Provider provider, ModelRoute route, Duration remaining) throws UpstreamException;
    }

    private final GatewayConfig config;
    private final ProviderHealth health;

    public FailoverExecutor(GatewayConfig config, ProviderHealth health) {
        this.config = config;
        this.health = health;
    }

    public <T> Served<T> execute(List<ModelRoute> chain, Duration budget, Call<T> call) throws AllFailed {
        long deadline = System.nanoTime() + budget.toNanos();
        GatewayConfig.RetryPolicy retry = config.retry();
        ModelRoute primary = chain.getFirst();
        List<Attempt> attempts = new ArrayList<>();
        UpstreamException last = null;

        for (ModelRoute route : health.order(chain)) {
            Provider provider = config.provider(route.provider());
            for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AllFailed(last != null ? last : UpstreamException.of(
                            UpstreamException.Kind.TOTAL_TIMEOUT, "request deadline exceeded"), attempts);
                }
                long start = System.nanoTime();
                try {
                    T value = call.apply(provider, route, Duration.ofNanos(remaining));
                    long ms = millisSince(start);
                    health.recordSuccess(route.provider(), ms, false);
                    attempts.add(new Attempt(route.provider(), route.model(), attempt, "ok", ms));
                    return new Served<>(value, route, !route.equals(primary), attempts);
                } catch (UpstreamException e) {
                    long ms = millisSince(start);
                    attempts.add(new Attempt(route.provider(), route.model(), attempt, e.label(), ms));
                    last = e;
                    if (e.isCallerError()) {
                        throw new AllFailed(e, attempts);
                    }
                    health.recordFailure(route.provider(), ms, e.label() + ": " + e.getMessage(), false);
                    if (!e.retryOnSameProvider() || attempt == retry.maxAttempts()) {
                        break; // next provider in the chain
                    }
                    sleep(backoff(retry, attempt), deadline);
                }
            }
        }
        throw new AllFailed(last, attempts);
    }

    /** initial * multiplier^(attempt-1), with "equal jitter" so retries from many clients spread out. */
    static Duration backoff(GatewayConfig.RetryPolicy retry, int attempt) {
        double base = retry.initialBackoff().toMillis() * Math.pow(retry.multiplier(), attempt - 1);
        double jittered = base / 2 + ThreadLocalRandom.current().nextDouble() * base / 2;
        return Duration.ofMillis((long) jittered);
    }

    private static void sleep(Duration pause, long deadline) {
        long ms = Math.min(pause.toMillis(), Math.max(0, (deadline - System.nanoTime()) / 1_000_000));
        try {
            Thread.sleep(ms); // cheap on a virtual thread
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
