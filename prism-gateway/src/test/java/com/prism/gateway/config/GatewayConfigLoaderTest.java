package com.prism.gateway.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.prism.gateway.TestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayConfigLoaderTest {

    private JsonNode sample() throws Exception {
        return TestFixtures.MAPPER.readTree(Files.readString(TestFixtures.DATA_DIR.resolve("gateway_config.sample.json")));
    }

    @Test
    void loadsTheProvidedConfigUnchanged() throws Exception {
        GatewayConfig config = GatewayConfigLoader.build(sample(), TestFixtures.props());
        assertThat(config.providers()).containsOnlyKeys("alpha", "beta");
        assertThat(config.aliasChains().get("fast")).extracting(GatewayConfig.ModelRoute::label)
                .containsExactly("alpha/alpha-small", "beta/beta-small");
        assertThat(config.aliasChains().get("smart")).extracting(GatewayConfig.ModelRoute::label)
                .containsExactly("alpha/alpha-large", "beta/beta-large");
        assertThat(config.isAuto("auto")).isTrue();
        assertThat(config.tierFor("simple")).isEqualTo("fast");
        assertThat(config.tierFor("complex")).isEqualTo("smart");
        assertThat(config.retry().maxAttempts()).isEqualTo(3);
        assertThat(config.retry().initialBackoff()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void deploymentOverridesDoNotTouchNames() throws Exception {
        PrismProperties base = TestFixtures.props();
        PrismProperties props = new PrismProperties(base.instanceId(), base.adminToken(), base.dataDir(),
                base.gatewayConfigFile(),
                new PrismProperties.Upstream(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10),
                        Duration.ofSeconds(30), Map.of("alpha", new PrismProperties.ProviderOverride(
                        "http://mock-alpha:9001/v1/", "from-env", Duration.ofSeconds(5), false))),
                base.cache(), base.router(), base.health());
        GatewayConfig config = GatewayConfigLoader.build(sample(), props);
        GatewayConfig.Provider alpha = config.provider("alpha");
        assertThat(alpha.baseUrl()).isEqualTo("http://mock-alpha:9001/v1");
        assertThat(alpha.apiKey()).isEqualTo("from-env");
        assertThat(alpha.firstByteTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(alpha.probe()).isFalse();
        assertThat(alpha.toString()).doesNotContain("from-env"); // secrets stay out of logs
        assertThat(config.provider("beta").apiKey()).isEqualTo("mock-key-beta");
    }

    private JsonNode extras() throws Exception {
        return TestFixtures.MAPPER.readTree(Files.readString(TestFixtures.DATA_DIR.resolve(GatewayConfigLoader.EXTRAS_FILE)));
    }

    private PrismProperties withKey(String providerName, String key) {
        PrismProperties base = TestFixtures.props();
        return new PrismProperties(base.instanceId(), base.adminToken(), base.dataDir(), base.gatewayConfigFile(),
                new PrismProperties.Upstream(base.upstream().connectTimeout(), base.upstream().firstByteTimeout(),
                        base.upstream().streamIdleTimeout(), base.upstream().totalTimeout(),
                        Map.of(providerName, new PrismProperties.ProviderOverride("", key, null, null))),
                base.cache(), base.router(), base.health());
    }

    @Test
    void optionalProviderIsIgnoredWithoutAKey() throws Exception {
        PrismProperties props = withKey("openrouter", "");
        GatewayConfig config = GatewayConfigLoader.build(GatewayConfigLoader.withOptionalProviders(sample(), extras(), props), props);
        assertThat(config.providers()).containsOnlyKeys("alpha", "beta");
        assertThat(config.aliasChains().get("fast")).extracting(GatewayConfig.ModelRoute::label)
                .containsExactly("alpha/alpha-small", "beta/beta-small");
    }

    @Test
    void optionalProviderIsAppendedLastWhenAKeyIsSet() throws Exception {
        PrismProperties props = withKey("openrouter", "sk-or-test");
        GatewayConfig config = GatewayConfigLoader.build(GatewayConfigLoader.withOptionalProviders(sample(), extras(), props), props);
        assertThat(config.providers()).containsOnlyKeys("alpha", "beta", "openrouter");
        assertThat(config.aliasChains().get("fast")).extracting(GatewayConfig.ModelRoute::label)
                .containsExactly("alpha/alpha-small", "beta/beta-small", "openrouter/openrouter-small");
        assertThat(config.aliasChains().get("smart")).extracting(GatewayConfig.ModelRoute::label)
                .containsExactly("alpha/alpha-large", "beta/beta-large", "openrouter/openrouter-large");
        GatewayConfig.Provider openrouter = config.provider("openrouter");
        assertThat(openrouter.apiKey()).isEqualTo("sk-or-test");
        assertThat(openrouter.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(openrouter.probe()).isFalse();                       // probes would spend billed requests
        assertThat(openrouter.firstByteTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(openrouter.upstreamModel("openrouter-small")).isEqualTo("openai/gpt-4.1-nano");
        assertThat(openrouter.upstreamModel("openrouter-large")).isEqualTo("openai/gpt-4.1-mini");
        assertThat(openrouter.toString()).doesNotContain("sk-or-test");
        assertThat(config.provider("alpha").upstreamModel("alpha-small")).isEqualTo("alpha-small"); // mocks unchanged
    }

    @Test
    void refusesAChainThatPointsAtAnUnknownProvider() throws Exception {
        JsonNode broken = TestFixtures.MAPPER.readTree("""
                {"providers":[{"name":"alpha","base_url":"http://a","api_key":"k"}],
                 "model_aliases":{"fast":{"primary":"gamma-small","fallbacks":[]}}}""");
        assertThatThrownBy(() -> GatewayConfigLoader.build(broken, TestFixtures.props()))
                .hasMessageContaining("gamma-small");
    }
}
