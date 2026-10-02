package com.prism.gateway.routing;

import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.GatewayConfig.ModelRoute;
import com.prism.gateway.config.PrismProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks how each provider is doing, from two sources: real traffic (passive) and the
 * {@link HealthProber}'s synthetic completions (active). A provider's /health endpoint alone is not
 * trusted: the provided mock reports "ok" on /health even while every completion returns 503.
 *
 * <ul>
 *   <li>UNHEALTHY: N consecutive failures, or an error rate above the configured threshold over the window.</li>
 *   <li>DEGRADED: alive, but p95 latency above the configured threshold.</li>
 * </ul>
 * Unhealthy and degraded providers are moved to the back of a fallback chain, never removed from it,
 * so a request can still succeed on them if everything else is failing too.
 */
@Component
public class ProviderHealth {

    public enum State { HEALTHY, DEGRADED, UNHEALTHY }

    public record Snapshot(String provider, State state, int samples, double errorRate, long p50Ms, long p95Ms,
                           int consecutiveFailures, String lastError, Instant lastErrorAt,
                           Instant lastProbeAt, Boolean lastProbeOk) {
    }

    private record Sample(long atMillis, boolean ok, long latencyMs) {
    }

    private static final class Stats {
        final Deque<Sample> window = new ArrayDeque<>();
        int consecutiveFailures;
        String lastError;
        Instant lastErrorAt;
        Instant lastProbeAt;
        Boolean lastProbeOk;
    }

    private final Map<String, Stats> stats = new ConcurrentHashMap<>();
    private final GatewayConfig.DegradationPolicy policy;
    private final int minSamples;
    private final int unhealthyAfterFailures;
    private final Clock clock;

    @Autowired
    public ProviderHealth(GatewayConfig config, PrismProperties props) {
        this(config.degradation(), props.health().minSamples(), props.health().unhealthyAfterFailures(), Clock.systemUTC());
        config.providers().keySet().forEach(p -> stats.put(p, new Stats()));
    }

    ProviderHealth(GatewayConfig.DegradationPolicy policy, int minSamples, int unhealthyAfterFailures, Clock clock) {
        this.policy = policy;
        this.minSamples = minSamples;
        this.unhealthyAfterFailures = unhealthyAfterFailures;
        this.clock = clock;
    }

    public void recordSuccess(String provider, long latencyMs, boolean probe) {
        Stats s = stats.computeIfAbsent(provider, k -> new Stats());
        synchronized (s) {
            boolean recoveringFromOutage = s.consecutiveFailures >= unhealthyAfterFailures;
            s.consecutiveFailures = 0;
            if (recoveringFromOutage) {
                // First success after an outage (a streak of failures): forget the outage so traffic
                // returns now. A provider that is merely flaky (high error rate, no streak) keeps its
                // history, so one lucky success does not make it look healthy.
                s.window.removeIf(sample -> !sample.ok());
            }
            add(s, new Sample(clock.millis(), true, latencyMs));
            if (probe) {
                s.lastProbeAt = clock.instant();
                s.lastProbeOk = true;
            }
        }
    }

    public void recordFailure(String provider, long latencyMs, String error, boolean probe) {
        Stats s = stats.computeIfAbsent(provider, k -> new Stats());
        synchronized (s) {
            s.consecutiveFailures++;
            s.lastError = error;
            s.lastErrorAt = clock.instant();
            add(s, new Sample(clock.millis(), false, latencyMs));
            if (probe) {
                s.lastProbeAt = clock.instant();
                s.lastProbeOk = false;
            }
        }
    }

    public State state(String provider) {
        Stats s = stats.get(provider);
        if (s == null) {
            return State.HEALTHY;
        }
        synchronized (s) {
            return stateOf(s);
        }
    }

    /** The chain in the order to try it: healthy first, then degraded, then unhealthy; stable otherwise. */
    public List<ModelRoute> order(List<ModelRoute> chain) {
        List<ModelRoute> ordered = new ArrayList<>(chain);
        ordered.sort(Comparator.comparingInt(r -> state(r.provider()).ordinal()));
        return ordered;
    }

    public List<Snapshot> snapshot() {
        List<Snapshot> out = new ArrayList<>();
        Map<String, Stats> sorted = new LinkedHashMap<>();
        stats.keySet().stream().sorted().forEach(k -> sorted.put(k, stats.get(k)));
        sorted.forEach((name, s) -> {
            synchronized (s) {
                prune(s);
                List<Long> latencies = s.window.stream().filter(Sample::ok).map(Sample::latencyMs).sorted().toList();
                out.add(new Snapshot(name, stateOf(s), s.window.size(), errorRate(s),
                        percentile(latencies, 0.50), percentile(latencies, 0.95),
                        s.consecutiveFailures, s.lastError, s.lastErrorAt, s.lastProbeAt, s.lastProbeOk));
            }
        });
        return out;
    }

    private State stateOf(Stats s) {
        prune(s);
        if (s.consecutiveFailures >= unhealthyAfterFailures) {
            return State.UNHEALTHY;
        }
        if (s.window.size() >= minSamples && errorRate(s) >= policy.errorRateThreshold()) {
            return State.UNHEALTHY;
        }
        List<Long> latencies = s.window.stream().filter(Sample::ok).map(Sample::latencyMs).sorted().toList();
        if (latencies.size() >= minSamples && percentile(latencies, 0.95) > policy.p95Latency().toMillis()) {
            return State.DEGRADED;
        }
        return State.HEALTHY;
    }

    private void add(Stats s, Sample sample) {
        s.window.addLast(sample);
        prune(s);
    }

    private void prune(Stats s) {
        long cutoff = clock.millis() - policy.window().toMillis();
        while (!s.window.isEmpty() && s.window.peekFirst().atMillis() < cutoff) {
            s.window.removeFirst();
        }
    }

    private static double errorRate(Stats s) {
        if (s.window.isEmpty()) {
            return 0.0;
        }
        long failures = s.window.stream().filter(sample -> !sample.ok()).count();
        return (double) failures / s.window.size();
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }
}
