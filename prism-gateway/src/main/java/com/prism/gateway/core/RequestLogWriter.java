package com.prism.gateway.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.gateway.limits.BudgetService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/**
 * Writes the request log row and settles the budget reservation in a single transaction, so the
 * spend used for budget checks and the per-request log behind the usage API can never disagree.
 * It runs before the response is released (for streams: before the final [DONE]), so a client that
 * queries the usage API right after a response already sees that request counted. Writing is
 * idempotent per request: the pipeline may call it early on the happy path and again from a
 * finally block, and only the first call writes.
 */
@Component
public class RequestLogWriter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogWriter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BudgetService budget;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public RequestLogWriter(JdbcTemplate jdbc, TransactionTemplate tx, BudgetService budget, ObjectMapper mapper,
                            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.budget = budget;
        this.mapper = mapper;
        this.meters = meters;
    }

    public void write(RequestTrace t, BudgetService.Reservation reservation) {
        if (!t.markLogged()) {
            return; // already written: one row and one budget settlement per request
        }
        long latency = t.elapsedMillis();
        try {
            tx.executeWithoutResult(status -> {
                if (reservation != null) {
                    budget.settle(reservation, t.costNanos(), t.promptTokens(), t.completionTokens(), true);
                }
                jdbc.update("""
                        INSERT INTO request_logs (request_id, tenant_id, team, key_hint, requested_model, resolved_alias,
                            resolved_provider, resolved_model, status, http_status, error_type, error_message, stream,
                            prompt_tokens, completion_tokens, cost_nanos, cost_estimated, cache, cache_similarity, cache_note,
                            fallback, retries, attempts, route_tier, route_reason, latency_ms, upstream_ms, ttft_ms,
                            gateway_instance)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                        """,
                        t.requestId(),
                        t.tenant() == null ? null : t.tenant().id(),
                        t.tenant() == null ? null : t.tenant().team(),
                        t.tenant() == null ? null : t.tenant().keyHint(),
                        t.requestedModel(), t.resolvedAlias(), t.provider(), t.model(),
                        t.status(), t.httpStatus(), t.errorType(), t.errorMessage(), t.isStream(),
                        t.promptTokens(), t.completionTokens(), t.costNanos(), t.costEstimated(),
                        t.cache(), t.cacheSimilarity(), t.cacheNote(),
                        t.fallback(), t.retries(), attemptsJson(t), t.routeTier(), t.routeReason(),
                        (int) latency, t.upstreamMs() == null ? null : t.upstreamMs().intValue(),
                        t.ttftMs() == null ? null : t.ttftMs().intValue(), t.instanceId());
            });
        } catch (RuntimeException e) {
            // The caller already got (or is getting) their answer; losing a log row is bad, failing
            // the request because of it would be worse. Loud error so it gets noticed.
            log.error("Could not write request log {} (status {}): {}", t.requestId(), t.status(), e.getMessage(), e);
        }
        record(t, latency);
    }

    private void record(RequestTrace t, long latency) {
        String provider = t.provider() == null ? "none" : t.provider();
        Counter.builder("prism.requests")
                .tag("status", t.status()).tag("provider", provider).tag("cache", t.cache())
                .tag("fallback", Boolean.toString(t.fallback()))
                .register(meters).increment();
        Timer.builder("prism.request.latency").tag("status", t.status()).publishPercentileHistogram()
                .register(meters).record(Duration.ofMillis(latency));
        if (t.upstreamMs() != null && !t.isStream()) {
            Timer.builder("prism.gateway.overhead").publishPercentileHistogram().register(meters)
                    .record(Duration.ofMillis(Math.max(0, latency - t.upstreamMs())));
        }
        if (t.costNanos() > 0) {
            Counter.builder("prism.cost.nanos").tag("provider", provider).register(meters).increment(t.costNanos());
        }
    }

    private String attemptsJson(RequestTrace t) {
        if (t.attempts() == null || t.attempts().isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(t.attempts());
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
