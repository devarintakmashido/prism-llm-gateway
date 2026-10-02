package com.prism.gateway.core;

import com.prism.gateway.config.GatewayConfig.ModelRoute;
import com.prism.gateway.routing.FailoverExecutor.Attempt;
import com.prism.gateway.tenant.Tenant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Supplier;

/**
 * Everything that happens to one request, collected as it goes: who sent it, how it was routed,
 * what the cache and providers did, what it cost, and how long each stage took. At the end it
 * becomes one request_logs row and the Server-Timing response header.
 */
public final class RequestTrace {

    private final String requestId;
    private final String instanceId;
    private final long startNanos = System.nanoTime();
    private final Map<String, Double> stageMillis = new LinkedHashMap<>();

    Tenant tenant;
    String requestedModel;
    String resolvedAlias;
    String provider;
    String model;
    String status = "ok";
    int httpStatus = 200;
    String errorType;
    String errorMessage;
    boolean stream;
    int promptTokens;
    int completionTokens;
    long costNanos;
    boolean costEstimated;
    String cache = "none"; // hit | miss | bypass | disabled | none (rejected before the cache stage)
    Double cacheSimilarity;
    String cacheNote;
    boolean fallback;
    int retries;
    List<Attempt> attempts;
    String routeTier;
    String routeReason;
    Long upstreamMs;
    Long ttftMs;
    private boolean logged;

    public RequestTrace(String requestId, String instanceId) {
        this.requestId = requestId;
        this.instanceId = instanceId;
    }

    /** Runs one pipeline stage and records its duration for Server-Timing and the log. */
    public <T> T time(String stage, Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            stageMillis.merge(stage, (System.nanoTime() - start) / 1e6, Double::sum);
        }
    }

    public void recordStage(String stage, long startNanos) {
        stageMillis.merge(stage, (System.nanoTime() - startNanos) / 1e6, Double::sum);
    }

    /** e.g. "auth;dur=0.4, ratelimit;dur=0.9, route;dur=3.1, cache;dur=4.0, upstream;dur=212.7, total;dur=221.5" */
    public String serverTiming() {
        StringJoiner joiner = new StringJoiner(", ");
        stageMillis.forEach((stage, ms) -> joiner.add(String.format(Locale.ROOT, "%s;dur=%.1f", stage, ms)));
        joiner.add(String.format(Locale.ROOT, "total;dur=%.1f", (System.nanoTime() - startNanos) / 1e6));
        return joiner.toString();
    }

    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public long startNanos() {
        return startNanos;
    }

    public void fail(PrismError error, String message) {
        this.status = error.logStatus();
        this.httpStatus = error.httpStatus();
        this.errorType = error.type();
        this.errorMessage = message;
    }

    public void served(ModelRoute route, boolean fallback, List<Attempt> attempts, int retries) {
        this.provider = route.provider();
        this.model = route.model();
        this.fallback = fallback;
        this.attempts = attempts;
        this.retries = retries;
    }

    public void attempts(List<Attempt> attempts) {
        this.attempts = attempts;
        this.retries = Math.max(0, attempts.size() - 1);
    }

    // --- simple setters used by the pipeline ---

    public void tenant(Tenant tenant) {
        this.tenant = tenant;
    }

    public void requestedModel(String model) {
        this.requestedModel = model;
    }

    public void resolvedAlias(String alias) {
        this.resolvedAlias = alias;
    }

    public void stream(boolean stream) {
        this.stream = stream;
    }

    public void route(String tier, String reason) {
        this.routeTier = tier;
        this.routeReason = reason;
    }

    public void cache(String result, Double similarity, String note) {
        this.cache = result;
        this.cacheSimilarity = similarity;
        this.cacheNote = note;
    }

    public void usage(int promptTokens, int completionTokens, long costNanos, boolean estimated) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.costNanos = costNanos;
        this.costEstimated = estimated;
    }

    public void outcome(String status, int httpStatus, String errorType, String errorMessage) {
        this.status = status;
        this.httpStatus = httpStatus;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
    }

    public void upstreamMs(long ms) {
        this.upstreamMs = ms;
    }

    public void ttftMs(Long ms) {
        this.ttftMs = ms;
    }

    /** True the first time only: a request is logged, and its budget settled, exactly once. */
    boolean markLogged() {
        if (logged) {
            return false;
        }
        logged = true;
        return true;
    }

    public boolean logged() {
        return logged;
    }

    // --- read access for the log writer ---

    public String requestId() {
        return requestId;
    }

    public String instanceId() {
        return instanceId;
    }

    public Tenant tenant() {
        return tenant;
    }

    public String requestedModel() {
        return requestedModel;
    }

    public String resolvedAlias() {
        return resolvedAlias;
    }

    public String provider() {
        return provider;
    }

    public String model() {
        return model;
    }

    public String status() {
        return status;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String errorType() {
        return errorType;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean isStream() {
        return stream;
    }

    public int promptTokens() {
        return promptTokens;
    }

    public int completionTokens() {
        return completionTokens;
    }

    public long costNanos() {
        return costNanos;
    }

    public boolean costEstimated() {
        return costEstimated;
    }

    public String cache() {
        return cache;
    }

    public Double cacheSimilarity() {
        return cacheSimilarity;
    }

    public String cacheNote() {
        return cacheNote;
    }

    public boolean fallback() {
        return fallback;
    }

    public int retries() {
        return retries;
    }

    public List<Attempt> attempts() {
        return attempts;
    }

    public String routeTier() {
        return routeTier;
    }

    public String routeReason() {
        return routeReason;
    }

    public Long upstreamMs() {
        return upstreamMs;
    }

    public Long ttftMs() {
        return ttftMs;
    }
}
