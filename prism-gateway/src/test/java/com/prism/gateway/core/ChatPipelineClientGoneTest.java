package com.prism.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.gateway.TestFixtures;
import com.prism.gateway.cache.SemanticCache;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.GatewayConfig.ModelRoute;
import com.prism.gateway.embedding.EmbeddingService;
import com.prism.gateway.limits.BudgetService;
import com.prism.gateway.limits.RateLimiter;
import com.prism.gateway.metering.PriceTable;
import com.prism.gateway.provider.Completion;
import com.prism.gateway.provider.ProviderAdapter;
import com.prism.gateway.provider.Usage;
import com.prism.gateway.router.AutoRouter;
import com.prism.gateway.routing.FailoverExecutor;
import com.prism.gateway.routing.ProviderHealth;
import com.prism.gateway.tenant.TenantService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A client that disconnects while the gateway is writing to it must still leave exactly one request-log
 * row, and its budget reservation must still be settled. A real HTTP client cannot time a disconnect
 * reliably, so here the response's output stream fails the way Tomcat's does once the client has gone.
 * The log writer is the real one, over a mocked database, so its write-once rule is part of the test.
 */
class ChatPipelineClientGoneTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BudgetService budget = mock(BudgetService.class);
    private final SemanticCache cache = mock(SemanticCache.class);
    private final FailoverExecutor failover = mock(FailoverExecutor.class);
    private final BudgetService.Reservation reservation =
            new BudgetService.Reservation(1, LocalDate.of(2026, 10, 1), 155_250);
    private ChatPipeline pipeline;

    @BeforeEach
    void setUp() {
        TenantService tenants = mock(TenantService.class);
        when(tenants.authenticate(any())).thenReturn(TestFixtures.tenant(1, true, 0.9));
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.acquire(any(), any())).thenReturn(new RateLimiter.Decision(true, 60, 59, 0, false));
        when(budget.reserve(any(), anyLong())).thenReturn(reservation);
        GatewayConfig config = new GatewayConfig(
                Map.of("alpha", TestFixtures.provider("alpha", "http://unused")),
                Map.of("fast", List.of(new ModelRoute("alpha", "alpha-small"))), Set.of(), Map.of(),
                new GatewayConfig.RetryPolicy(1, Duration.ofMillis(1), 2.0),
                new GatewayConfig.DegradationPolicy(0.5, Duration.ofSeconds(60), Duration.ofSeconds(5)));
        RequestLogWriter logs = new RequestLogWriter(jdbc, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                budget, TestFixtures.MAPPER, new SimpleMeterRegistry());
        pipeline = new ChatPipeline(TestFixtures.MAPPER, TestFixtures.props(), config, tenants, limiter, budget,
                mock(PriceTable.class), mock(AutoRouter.class), mock(EmbeddingService.class), cache, failover,
                mock(ProviderAdapter.class), mock(ProviderHealth.class), logs);
    }

    @Test
    void aClientThatLeavesDuringACachedStreamIsStillLoggedAndSettledOnce() throws Exception {
        ObjectNode cached = TestFixtures.MAPPER.createObjectNode().put("object", "chat.completion");
        cached.putArray("choices").addObject().putObject("message").put("content", "Go to Settings, then Security.");
        when(cache.lookup(any(), any(), any())).thenReturn(new SemanticCache.Lookup(
                new SemanticCache.Hit(7, cached, "alpha/alpha-small", 0.97, false), 0.97, "semantic match"));

        pipeline.handle(body(true), new MockHttpServletRequest(), new ClientGoneResponse());

        assertThat(logInserts()).hasSize(1);
        assertThat(logInserts().getFirst()).contains("client_cancelled");
        verify(budget, times(1)).settle(eq(reservation), anyLong(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    void aServedRequestIsLoggedOnceEvenWhenTheClientLeavesBeforeTheBody() throws Exception {
        when(cache.lookup(any(), any(), any())).thenReturn(new SemanticCache.Lookup(null, null, "no entries in scope"));
        JsonNode json = TestFixtures.MAPPER.readTree("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":3,"completion_tokens":2}}""");
        Completion completion = new Completion(json.toString().getBytes(UTF_8), json, new Usage(3, 2));
        when(failover.<Completion>execute(any(), any(), any())).thenReturn(
                new FailoverExecutor.Served<>(completion, new ModelRoute("alpha", "alpha-small"), false, List.of()));

        pipeline.handle(body(false), new MockHttpServletRequest(), new ClientGoneResponse());

        // Logged before the body was written, so the usage API already counts it, and not again afterwards.
        assertThat(logInserts()).hasSize(1);
        assertThat(logInserts().getFirst()).contains("ok").doesNotContain("client_cancelled");
        verify(budget, times(1)).settle(eq(reservation), anyLong(), anyInt(), anyInt(), anyBoolean());
    }

    private static byte[] body(boolean stream) {
        return ("{\"model\":\"fast\",\"stream\":" + stream
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"How do I reset my password?\"}]}").getBytes(UTF_8);
    }

    /** The arguments of every INSERT INTO request_logs the writer issued. */
    private List<List<Object>> logInserts() {
        return Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("update")
                        && i.getArgument(0).toString().contains("INSERT INTO request_logs"))
                .map(i -> Arrays.asList(i.getArguments()))
                .toList();
    }

    /** A response whose client has disconnected: every write fails, as with Tomcat's ClientAbortException. */
    private static final class ClientGoneResponse extends MockHttpServletResponse {
        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }

                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Broken pipe");
                }
            };
        }
    }
}
