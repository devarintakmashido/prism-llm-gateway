package com.prism.gateway.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.gateway.TestFixtures;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.core.SseRelay;
import com.prism.gateway.provider.ProviderAdapter.CallOptions;
import com.prism.gateway.provider.UpstreamException.Kind;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleAdapterTest {

    private static FakeProvider fake;
    private static OpenAiCompatibleAdapter adapter;
    private static GatewayConfig.Provider provider;
    private static final CallOptions OPTIONS =
            new CallOptions(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500));

    @BeforeAll
    static void start() throws Exception {
        fake = new FakeProvider();
        adapter = new OpenAiCompatibleAdapter(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(500)).build(), TestFixtures.MAPPER);
        provider = new GatewayConfig.Provider("fake", fake.baseUrl(), FakeProvider.API_KEY, Duration.ofMillis(500), false);
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    private static ObjectNode request() {
        ObjectNode r = TestFixtures.MAPPER.createObjectNode();
        r.putArray("messages").addObject().put("role", "user").put("content", "hi");
        return r;
    }

    @Test
    void readsProviderUsageFromCompletions() throws Exception {
        Completion c = adapter.complete(provider, "ok", request(), OPTIONS);
        assertThat(c.usage()).isEqualTo(new Usage(3, 2));
        assertThat(c.content()).isEqualTo("hello there");
    }

    @Test
    void sendsTheProvidersOwnModelIdWhenTheProviderMapsOne() throws Exception {
        // "openrouter-small" is Prism's logical name; the fake only knows "ok", so a 200 proves the mapping was applied.
        GatewayConfig.Provider mapped = new GatewayConfig.Provider("fake", fake.baseUrl(), FakeProvider.API_KEY,
                Duration.ofMillis(500), false, java.util.Map.of("openrouter-small", "ok"));
        assertThat(adapter.complete(mapped, "openrouter-small", request(), OPTIONS).content()).isEqualTo("hello there");
        assertThatThrownBy(() -> adapter.complete(provider, "openrouter-small", request(), OPTIONS))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void classifiesProviderErrorsAndRedactsTheProviderKey() {
        assertThatThrownBy(() -> adapter.complete(provider, "down", request(), OPTIONS))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.status()).isEqualTo(503);
                    assertThat(e.retryOnSameProvider()).isFalse();
                    assertThat(e.getMessage()).doesNotContain(FakeProvider.API_KEY).contains("[redacted]");
                });
    }

    @Test
    void aHungProviderHitsTheFirstByteTimeoutInsteadOfHangingTheClient() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> adapter.complete(provider, "slow", request(), OPTIONS))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.isTimeout()).isTrue());
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void garbageFromAProviderIsMalformedAndRetryable() {
        assertThatThrownBy(() -> adapter.complete(provider, "garbage", request(), OPTIONS))
                .isInstanceOfSatisfying(UpstreamException.class, e -> {
                    assertThat(e.kind()).isEqualTo(Kind.MALFORMED);
                    assertThat(e.retryOnSameProvider()).isTrue();
                });
    }

    @Test
    void streamsArePassedThroughAndUsageIsCaptured() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (UpstreamStream s = adapter.stream(provider, "stream-ok", request(), OPTIONS)) {
            SseRelay.Outcome o = SseRelay.relay(s, out, TestFixtures.MAPPER, "req_1", System.nanoTime());
            assertThat(o.status()).isEqualTo(SseRelay.Status.COMPLETED);
            assertThat(o.content()).isEqualTo("one two three four ");
            assertThat(o.usage()).isEqualTo(new Usage(3, 4));
            assertThat(o.finishReason()).isEqualTo("stop");
        }
        String relayed = out.toString(StandardCharsets.UTF_8);
        assertThat(relayed.split("\n\n")).hasSize(6); // role + 4 words + final chunk; [DONE] is written by the pipeline
    }

    @Test
    void keepAliveCommentsAreSkipped() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (UpstreamStream s = adapter.stream(provider, "stream-keepalive", request(), OPTIONS)) {
            SseRelay.Outcome o = SseRelay.relay(s, out, TestFixtures.MAPPER, "req_2", System.nanoTime());
            assertThat(o.status()).isEqualTo(SseRelay.Status.COMPLETED);
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("OPENROUTER PROCESSING");
    }

    @Test
    void aStreamThatDiesMidwayEndsWithAnErrorEventNotASplice() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (UpstreamStream s = adapter.stream(provider, "stream-cut", request(), OPTIONS)) {
            SseRelay.Outcome o = SseRelay.relay(s, out, TestFixtures.MAPPER, "req_3", System.nanoTime());
            assertThat(o.status()).isEqualTo(SseRelay.Status.STREAM_ERROR);
            assertThat(o.content()).isEqualTo("one two ");
            assertThat(o.usage()).isNull(); // the pipeline will estimate and flag it
        }
        String relayed = out.toString(StandardCharsets.UTF_8);
        assertThat(relayed).contains("\"finish_reason\":\"error\"").contains("stream_interrupted");
    }

    @Test
    void aStreamThatGoesSilentIsCutByTheIdleTimeout() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long start = System.nanoTime();
        try (UpstreamStream s = adapter.stream(provider, "stream-idle", request(), OPTIONS)) {
            SseRelay.Outcome o = SseRelay.relay(s, out, TestFixtures.MAPPER, "req_4", System.nanoTime());
            assertThat(o.status()).isEqualTo(SseRelay.Status.STREAM_ERROR);
            assertThat(o.error().kind()).isEqualTo(Kind.STREAM_IDLE_TIMEOUT);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void anErrorStatusBeforeTheFirstEventCanStillFailOver() {
        assertThatThrownBy(() -> adapter.stream(provider, "down", request(), OPTIONS))
                .isInstanceOfSatisfying(UpstreamException.class, e -> assertThat(e.status()).isEqualTo(503));
    }
}
