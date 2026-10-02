package com.prism.gateway.routing;

import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.GatewayConfig.ModelRoute;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.prism.gateway.routing.ProviderHealth.State.DEGRADED;
import static com.prism.gateway.routing.ProviderHealth.State.HEALTHY;
import static com.prism.gateway.routing.ProviderHealth.State.UNHEALTHY;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderHealthTest {

    private final GatewayConfig.DegradationPolicy policy =
            new GatewayConfig.DegradationPolicy(0.5, Duration.ofSeconds(60), Duration.ofMillis(1000));

    private ProviderHealth health(Clock clock) {
        return new ProviderHealth(policy, 5, 3, clock);
    }

    @Test
    void consecutiveFailuresMarkAProviderUnhealthy() {
        ProviderHealth h = health(Clock.systemUTC());
        h.recordFailure("alpha", 3, "http_503", false);
        h.recordFailure("alpha", 3, "http_503", false);
        assertThat(h.state("alpha")).isEqualTo(HEALTHY);
        h.recordFailure("alpha", 3, "http_503", true);
        assertThat(h.state("alpha")).isEqualTo(UNHEALTHY);
    }

    @Test
    void oneSuccessfulProbeBringsItBackImmediately() {
        ProviderHealth h = health(Clock.systemUTC());
        for (int i = 0; i < 10; i++) {
            h.recordFailure("alpha", 3, "http_503", true);
        }
        assertThat(h.state("alpha")).isEqualTo(UNHEALTHY);
        h.recordSuccess("alpha", 4, true);
        assertThat(h.state("alpha")).isEqualTo(HEALTHY);
    }

    @Test
    void highErrorRateOverTheWindowIsUnhealthyEvenWithoutAStreak() {
        ProviderHealth h = health(Clock.systemUTC());
        for (int i = 0; i < 6; i++) {
            h.recordFailure("alpha", 3, "http_500", false);
            h.recordFailure("alpha", 3, "http_500", false);
            h.recordSuccess("alpha", 3, false); // breaks the streak, error rate stays at 2/3
        }
        assertThat(h.state("alpha")).isEqualTo(UNHEALTHY);
    }

    @Test
    void slowButAliveIsDegraded() {
        ProviderHealth h = health(Clock.systemUTC());
        for (int i = 0; i < 10; i++) {
            h.recordSuccess("alpha", 2500, false);
        }
        assertThat(h.state("alpha")).isEqualTo(DEGRADED);
    }

    @Test
    void oldSamplesExpireWithTheWindow() {
        MutableClock clock = new MutableClock();
        ProviderHealth h = health(clock);
        for (int i = 0; i < 6; i++) {
            h.recordFailure("alpha", 3, "http_500", false);
            h.recordSuccess("alpha", 3, false);
        }
        assertThat(h.state("alpha")).isEqualTo(UNHEALTHY);
        clock.advance(Duration.ofSeconds(61));
        assertThat(h.state("alpha")).isEqualTo(HEALTHY);
    }

    @Test
    void chainIsReorderedHealthyFirstButNeverShortened() {
        ProviderHealth h = health(Clock.systemUTC());
        for (int i = 0; i < 3; i++) {
            h.recordFailure("alpha", 3, "http_503", false);
        }
        List<ModelRoute> chain = List.of(new ModelRoute("alpha", "alpha-small"), new ModelRoute("beta", "beta-small"));
        assertThat(h.order(chain)).extracting(ModelRoute::provider).containsExactly("beta", "alpha");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
