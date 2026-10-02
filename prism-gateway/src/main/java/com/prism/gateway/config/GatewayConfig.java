package com.prism.gateway.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The provider registry and alias table, loaded from data/gateway_config.sample.json.
 * Tier aliases ("fast", "smart") map to an ordered chain: the primary model, then its fallbacks.
 * Auto aliases ("auto") have no chain of their own; the router picks a tier alias for them.
 */
public record GatewayConfig(Map<String, Provider> providers,
                            Map<String, List<ModelRoute>> aliasChains,
                            Set<String> autoAliases,
                            Map<String, String> difficultyTiers,
                            RetryPolicy retry,
                            DegradationPolicy degradation) {

    /**
     * An upstream provider. apiKey is a gateway secret: never logged, never returned to callers.
     * modelIds maps Prism's logical model names ("openrouter-small") to the provider's own model ids
     * ("openai/gpt-4.1-nano"); a model without an entry is sent upstream under its Prism name, as the mocks expect.
     */
    public record Provider(String name, String baseUrl, String apiKey, Duration firstByteTimeout, boolean probe,
                           Map<String, String> modelIds) {

        public Provider(String name, String baseUrl, String apiKey, Duration firstByteTimeout, boolean probe) {
            this(name, baseUrl, apiKey, firstByteTimeout, probe, Map.of());
        }

        public String upstreamModel(String prismModel) {
            return modelIds.getOrDefault(prismModel, prismModel);
        }

        @Override
        public String toString() {
            return "Provider[" + name + " " + baseUrl + "]"; // keeps the key out of logs
        }
    }

    /** One concrete target: which provider, which model. Rendered as "alpha/alpha-small". */
    public record ModelRoute(String provider, String model) {
        public String label() {
            return provider + "/" + model;
        }
    }

    public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier) {
    }

    public record DegradationPolicy(double errorRateThreshold, Duration window, Duration p95Latency) {
    }

    public boolean isAuto(String model) {
        return autoAliases.contains(model);
    }

    public boolean isTierAlias(String model) {
        return aliasChains.containsKey(model);
    }

    /** The tier alias for a difficulty label, e.g. "complex" -> "smart". */
    public String tierFor(String difficulty) {
        return difficultyTiers.get(difficulty);
    }

    /** Model names follow the "<provider>-<size>" convention of the provided config. */
    public Optional<String> providerFor(String model) {
        int dash = model.lastIndexOf('-');
        if (dash > 0 && providers.containsKey(model.substring(0, dash))) {
            return Optional.of(model.substring(0, dash));
        }
        return Optional.empty();
    }

    public Provider provider(String name) {
        Provider p = providers.get(name);
        if (p == null) {
            throw new IllegalArgumentException("Unknown provider " + name);
        }
        return p;
    }
}
