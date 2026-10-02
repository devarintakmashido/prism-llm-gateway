package com.prism.gateway.routing;

import com.prism.gateway.TestFixtures;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.GatewayConfig.ModelRoute;
import com.prism.gateway.provider.UpstreamException;
import com.prism.gateway.provider.UpstreamException.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverExecutorTest {

    private static final ModelRoute ALPHA = new ModelRoute("alpha", "alpha-small");
    private static final ModelRoute BETA = new ModelRoute("beta", "beta-small");
    private static final List<ModelRoute> CHAIN = List.of(ALPHA, BETA);

    private ProviderHealth health;
    private FailoverExecutor failover;
    private final List<String> calls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        GatewayConfig.DegradationPolicy degradation =
                new GatewayConfig.DegradationPolicy(0.5, Duration.ofSeconds(60), Duration.ofSeconds(5));
        GatewayConfig config = new GatewayConfig(
                Map.of("alpha", TestFixtures.provider("alpha", "http://alpha"), "beta", TestFixtures.provider("beta", "http://beta")),
                Map.of("fast", CHAIN), Set.of("auto"), Map.of("simple", "fast"),
                new GatewayConfig.RetryPolicy(3, Duration.ofMillis(1), 2.0), degradation);
        health = new ProviderHealth(degradation, 5, 3, Clock.systemUTC());
        failover = new FailoverExecutor(config, health);
    }

    private FailoverExecutor.Call<String> script(Map<String, List<UpstreamException>> failures) {
        return (provider, route, remaining) -> {
            calls.add(route.provider());
            List<UpstreamException> queue = failures.getOrDefault(route.provider(), List.of());
            long seen = calls.stream().filter(c -> c.equals(route.provider())).count();
            if (seen <= queue.size()) {
                throw queue.get((int) seen - 1);
            }
            return "answer from " + route.provider();
        };
    }

    @Test
    void healthyPrimaryServesWithoutFallback() throws Exception {
        var served = failover.execute(CHAIN, Duration.ofSeconds(5), script(Map.of()));
        assertThat(served.value()).isEqualTo("answer from alpha");
        assertThat(served.fallback()).isFalse();
        assertThat(served.retries()).isZero();
    }

    @Test
    void providerDownFailsOverImmediatelyWithoutRetrying() throws Exception {
        var served = failover.execute(CHAIN, Duration.ofSeconds(5),
                script(Map.of("alpha", List.of(UpstreamException.http(503, "down", null)))));
        assertThat(served.value()).isEqualTo("answer from beta");
        assertThat(served.fallback()).isTrue();
        assertThat(calls).containsExactly("alpha", "beta"); // no second try on a 503
    }

    @Test
    void rateLimitedAndUnreachableProvidersAlsoFailOverImmediately() throws Exception {
        for (UpstreamException e : List.of(UpstreamException.http(429, "slow down", 5000L),
                UpstreamException.of(Kind.CONNECT_FAILED, "refused"),
                UpstreamException.of(Kind.FIRST_BYTE_TIMEOUT, "slow"))) {
            calls.clear();
            setUp();
            var served = failover.execute(CHAIN, Duration.ofSeconds(5), script(Map.of("alpha", List.of(e))));
            assertThat(served.route()).isEqualTo(BETA);
            assertThat(calls).containsExactly("alpha", "beta");
        }
    }

    @Test
    void transientErrorsAreRetriedOnTheSameProvider() throws Exception {
        var served = failover.execute(CHAIN, Duration.ofSeconds(5), script(Map.of("alpha",
                List.of(UpstreamException.http(500, "flaky", null), UpstreamException.http(502, "flaky", null)))));
        assertThat(served.value()).isEqualTo("answer from alpha");
        assertThat(served.fallback()).isFalse();
        assertThat(served.retries()).isEqualTo(2);
        assertThat(calls).containsExactly("alpha", "alpha", "alpha");
    }

    @Test
    void retriesAreBoundedThenTheNextProviderIsTried() throws Exception {
        UpstreamException flaky = UpstreamException.http(500, "flaky", null);
        var served = failover.execute(CHAIN, Duration.ofSeconds(5),
                script(Map.of("alpha", List.of(flaky, flaky, flaky))));
        assertThat(calls).containsExactly("alpha", "alpha", "alpha", "beta");
        assertThat(served.fallback()).isTrue();
    }

    @Test
    void callerErrorsAreNotRetriedAnywhere() {
        assertThatThrownBy(() -> failover.execute(CHAIN, Duration.ofSeconds(5),
                script(Map.of("alpha", List.of(UpstreamException.http(400, "bad temperature", null))))))
                .isInstanceOf(FailoverExecutor.AllFailed.class)
                .satisfies(e -> assertThat(((FailoverExecutor.AllFailed) e).last().isCallerError()).isTrue());
        assertThat(calls).containsExactly("alpha");
    }

    @Test
    void allProvidersFailingReportsEveryAttempt() {
        UpstreamException down = UpstreamException.http(503, "down", null);
        assertThatThrownBy(() -> failover.execute(CHAIN, Duration.ofSeconds(5),
                script(Map.of("alpha", List.of(down), "beta", List.of(down)))))
                .isInstanceOfSatisfying(FailoverExecutor.AllFailed.class,
                        e -> assertThat(e.attempts()).extracting(FailoverExecutor.Attempt::outcome)
                                .containsExactly("http_503", "http_503"));
    }

    @Test
    void aProviderKnownToBeDownIsTriedLast() throws Exception {
        for (int i = 0; i < 3; i++) {
            health.recordFailure("alpha", 5, "probe http_503", true);
        }
        assertThat(health.state("alpha")).isEqualTo(ProviderHealth.State.UNHEALTHY);
        var served = failover.execute(CHAIN, Duration.ofSeconds(5), script(Map.of()));
        assertThat(calls).containsExactly("beta"); // no wasted attempt on alpha
        assertThat(served.fallback()).isTrue();
    }
}
