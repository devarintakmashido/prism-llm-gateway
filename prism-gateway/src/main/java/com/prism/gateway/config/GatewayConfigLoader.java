package com.prism.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.gateway.config.GatewayConfig.DegradationPolicy;
import com.prism.gateway.config.GatewayConfig.ModelRoute;
import com.prism.gateway.config.GatewayConfig.Provider;
import com.prism.gateway.config.GatewayConfig.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link GatewayConfig} from the provided gateway_config.sample.json, keeping its provider
 * names, model names and aliases unchanged. Deployment details (base URLs, provider keys, timeouts)
 * can be overridden per provider from the environment without editing the file.
 *
 * <p>Optional providers (a real hosted one such as OpenRouter) live in a separate overlay file,
 * data/gateway_extras.json, so the provided config stays exactly as shipped. An overlay provider is
 * enabled only when it has an API key; without one it is skipped, together with its fallback entries,
 * and the gateway behaves as if the overlay did not exist.
 */
@Configuration
public class GatewayConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigLoader.class);
    static final String EXTRAS_FILE = "gateway_extras.json";

    @Bean
    GatewayConfig gatewayConfig(PrismProperties props, ObjectMapper mapper) throws IOException {
        Path file = props.dataDir().resolve(props.gatewayConfigFile());
        JsonNode root = mapper.readTree(Files.readString(file));
        Path extrasFile = props.dataDir().resolve(EXTRAS_FILE);
        if (Files.exists(extrasFile)) {
            root = withOptionalProviders(root, mapper.readTree(Files.readString(extrasFile)), props);
        }
        GatewayConfig config = build(root, props);
        log.info("Gateway config loaded from {}: providers={}, aliases={}, auto={}",
                file, config.providers().keySet(), config.aliasChains().keySet(), config.autoAliases());
        return config;
    }

    static GatewayConfig build(JsonNode root, PrismProperties props) {
        Map<String, PrismProperties.ProviderOverride> overrides =
                props.upstream().providers() == null ? Map.of() : props.upstream().providers();

        Map<String, Provider> providers = new LinkedHashMap<>();
        for (JsonNode p : root.path("providers")) {
            String name = p.get("name").asText();
            PrismProperties.ProviderOverride o = overrides.get(name);
            String baseUrl = o != null && notBlank(o.baseUrl()) ? o.baseUrl() : p.get("base_url").asText();
            String apiKey = o != null && notBlank(o.apiKey()) ? o.apiKey() : p.get("api_key").asText();
            Duration firstByte = o != null && o.firstByteTimeout() != null ? o.firstByteTimeout()
                    : p.has("first_byte_timeout_ms") ? Duration.ofMillis(p.get("first_byte_timeout_ms").asLong())
                    : props.upstream().firstByteTimeout();
            boolean probe = o != null && o.probe() != null ? o.probe() : p.path("probe").asBoolean(true);
            Map<String, String> modelIds = new LinkedHashMap<>();
            p.path("model_ids").properties().forEach(e -> modelIds.put(e.getKey(), e.getValue().asText()));
            providers.put(name, new Provider(name, stripSlash(baseUrl), apiKey, firstByte, probe, Map.copyOf(modelIds)));
        }

        Map<String, List<ModelRoute>> chains = new LinkedHashMap<>();
        Set<String> autoAliases = new LinkedHashSet<>();
        Map<String, String> difficultyTiers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> alias : root.path("model_aliases").properties()) {
            JsonNode route = alias.getValue();
            if (route.has("route_by_difficulty")) {
                autoAliases.add(alias.getKey());
                route.get("route_by_difficulty").properties()
                        .forEach(e -> difficultyTiers.put(e.getKey(), e.getValue().asText()));
                continue;
            }
            List<ModelRoute> chain = new ArrayList<>();
            chain.add(route(route.get("primary").asText(), providers));
            route.path("fallbacks").forEach(f -> chain.add(route(f.asText(), providers)));
            chains.put(alias.getKey(), List.copyOf(chain));
        }
        difficultyTiers.forEach((difficulty, tier) -> {
            if (!chains.containsKey(tier)) {
                throw new IllegalStateException("auto difficulty '" + difficulty + "' routes to unknown alias '" + tier + "'");
            }
        });

        JsonNode retry = root.path("retry");
        RetryPolicy retryPolicy = new RetryPolicy(
                retry.path("max_attempts").asInt(3),
                Duration.ofMillis(retry.path("initial_backoff_ms").asLong(200)),
                retry.path("backoff_multiplier").asDouble(2.0));

        JsonNode degradation = root.path("degradation");
        DegradationPolicy degradationPolicy = new DegradationPolicy(
                degradation.path("error_rate_threshold").asDouble(0.5),
                Duration.ofSeconds(degradation.path("window_seconds").asLong(60)),
                Duration.ofMillis(degradation.path("p95_latency_ms").asLong(5000)));

        return new GatewayConfig(Map.copyOf(providers), Map.copyOf(chains), Set.copyOf(autoAliases),
                Map.copyOf(difficultyTiers), retryPolicy, degradationPolicy);
    }

    /**
     * Merges the overlay into the provided config: each overlay provider that has a key (from the
     * environment override or the file) is added, and its models are appended to the end of the
     * named alias chains, so it only serves traffic after the provided providers have failed.
     */
    static JsonNode withOptionalProviders(JsonNode root, JsonNode extras, PrismProperties props) {
        Map<String, PrismProperties.ProviderOverride> overrides =
                props.upstream().providers() == null ? Map.of() : props.upstream().providers();
        ObjectNode merged = root.deepCopy();
        ArrayNode providers = (ArrayNode) merged.get("providers");
        Set<String> enabled = new HashSet<>();
        for (JsonNode p : extras.path("providers")) {
            String name = p.get("name").asText();
            PrismProperties.ProviderOverride o = overrides.get(name);
            if ((o != null && notBlank(o.apiKey())) || notBlank(p.path("api_key").asText())) {
                providers.add(p);
                enabled.add(name);
            } else {
                log.info("Optional provider '{}' has no API key: not enabled", name);
            }
        }
        JsonNode aliases = merged.path("model_aliases");
        extras.path("append_fallbacks").properties().forEach(e -> {
            if (!(aliases.get(e.getKey()) instanceof ObjectNode alias)) {
                return;
            }
            ArrayNode fallbacks = alias.get("fallbacks") instanceof ArrayNode a ? a : alias.putArray("fallbacks");
            for (JsonNode model : e.getValue()) {
                String m = model.asText();
                int dash = m.lastIndexOf('-');
                if (dash > 0 && enabled.contains(m.substring(0, dash))) {
                    fallbacks.add(m);
                }
            }
        });
        return merged;
    }

    private static ModelRoute route(String model, Map<String, Provider> providers) {
        int dash = model.lastIndexOf('-');
        String provider = dash > 0 ? model.substring(0, dash) : model;
        if (!providers.containsKey(provider)) {
            throw new IllegalStateException("Model '" + model + "' has no registered provider '" + provider + "'");
        }
        return new ModelRoute(provider, model);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
