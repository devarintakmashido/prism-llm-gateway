package com.prism.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.core.ChatRequest;
import com.prism.gateway.tenant.Tenant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared test data: the provided seed files live in ../data relative to the module. */
public final class TestFixtures {

    public static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Path DATA_DIR = Path.of("..", "data");

    private TestFixtures() {
    }

    public static PrismProperties props() {
        return new PrismProperties("test-instance", "test-admin-token", DATA_DIR, "gateway_config.sample.json",
                new PrismProperties.Upstream(Duration.ofMillis(500), Duration.ofMillis(800), Duration.ofMillis(800),
                        Duration.ofSeconds(5), Map.of()),
                new PrismProperties.Cache(Duration.ofHours(1), 150, 5, 5000),
                new PrismProperties.Router(1.5, 5, 0.5, "complex"),
                new PrismProperties.Health(false, Duration.ofSeconds(3), 5, 3, Duration.ofSeconds(2)));
    }

    public static GatewayConfig.Provider provider(String name, String baseUrl) {
        return new GatewayConfig.Provider(name, baseUrl, "secret-key-" + name, Duration.ofMillis(800), false);
    }

    public static Tenant tenant(long id, boolean cacheEnabled, double threshold) {
        return new Tenant(id, "team-" + id, "hint", 1_000_000_000L, 60, null, Set.of("fast", "smart", "auto"),
                cacheEnabled, threshold, "active");
    }

    public static ChatRequest chat(String json) {
        return ChatRequest.parse(json.getBytes(StandardCharsets.UTF_8), MAPPER);
    }

    public static ChatRequest userMessage(String model, String text) {
        try {
            return chat(MAPPER.writeValueAsString(Map.of("model", model,
                    "messages", List.of(Map.of("role", "user", "content", text)))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
