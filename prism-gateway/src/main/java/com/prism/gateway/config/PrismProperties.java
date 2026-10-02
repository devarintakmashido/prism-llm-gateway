package com.prism.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Gateway settings from application.yml (overridable with PRISM_* environment variables). */
@ConfigurationProperties(prefix = "prism")
public record PrismProperties(
        String instanceId,
        String adminToken,
        Path dataDir,
        String gatewayConfigFile,
        Upstream upstream,
        Cache cache,
        Router router,
        Health health) {

    /**
     * Timeouts for every upstream call. first-byte bounds how long we wait for response headers
     * (a slow or hung provider), stream-idle bounds the gap between two streamed chunks, and total
     * bounds a whole non-streaming request including retries and failover.
     */
    public record Upstream(Duration connectTimeout,
                           Duration firstByteTimeout,
                           Duration streamIdleTimeout,
                           Duration totalTimeout,
                           Map<String, ProviderOverride> providers) {
    }

    /** Per-provider overrides; base URL and API key usually come from the environment. */
    public record ProviderOverride(String baseUrl, String apiKey, Duration firstByteTimeout, Boolean probe) {
    }

    public record Cache(Duration ttl, int maxSemanticWords, int candidates, int maxEntriesPerTenant) {
    }

    public record Router(double knnWeight, int knnK, double uncertainMargin, String uncertainTier) {
    }

    /** The error-rate threshold, window and p95 limit come from the "degradation" block of the gateway config. */
    public record Health(boolean probeEnabled,
                         Duration probeInterval,
                         int minSamples,
                         int unhealthyAfterFailures,
                         Duration publishInterval) {
    }
}
