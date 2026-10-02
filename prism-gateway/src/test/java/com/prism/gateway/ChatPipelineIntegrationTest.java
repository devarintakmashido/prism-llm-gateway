package com.prism.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.common.Keys;
import com.prism.gateway.provider.FakeProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole data plane over HTTP, against real Postgres (pgvector) and Redis, with two in-process fakes
 * standing in for providers alpha and beta. It checks what only shows up end to end: the documented
 * order of rejections, the allowlist (including for auto), the x-prism-* header contract, exactly one
 * request-log row per request (rejections included), metering of streams, and failover.
 * <p>
 * The fakes answer every completion with 3 prompt and 2 completion tokens (streams: 3 and 4), so costs
 * are known exactly: alpha-small costs 3 x $0.15 + 2 x $0.60 per 1M tokens = 1,650 nano-USD.
 * Tests run in order because rate limits, budgets and provider health carry over between them.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatPipelineIntegrationTest {

    private static final String SEARCH = "prism-sk-search-1a2b3c";        // fast only, cache on
    private static final String RESEARCH = "prism-sk-research-4d5e6f";    // fast, smart, auto; cache off
    private static final String FREE = "prism-sk-free-7g8h9i";            // fast only, 10 requests a minute
    private static final String BUDGET_DEMO = "prism-sk-budget-demo-0j1k2l"; // $0.00001 a month
    private static final long ALPHA_SMALL_COST = 1_650;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static final FakeProvider ALPHA = startFake();
    static final FakeProvider BETA = startFake();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("prism.data-dir", () -> TestFixtures.DATA_DIR.toAbsolutePath().normalize().toString());
        registry.add("prism.health.probe-enabled", () -> "false");
        registry.add("prism.upstream.providers.alpha.base-url", ALPHA::baseUrl);
        registry.add("prism.upstream.providers.beta.base-url", BETA::baseUrl);
        registry.add("prism.upstream.providers.openrouter.api-key", () -> ""); // never a real, billed provider
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    @AfterAll
    static void stopFakes() {
        ALPHA.close();
        BETA.close();
    }

    @Test
    @Order(1)
    void rejectionsAreDistinctComeInTheDocumentedOrderAndNeverReachAProvider() throws Exception {
        int upstreamBefore = ALPHA.requests() + BETA.requests();
        long logsBefore = logCount();

        assertRejected(chat(null, body("fast", "hello", false)), 401, "authentication_error");
        assertRejected(ask("prism-sk-wrong-000000", "fast", "hello"), 401, "authentication_error");
        // Authentication comes first: a bad key asking for a bad model is still a 401.
        assertRejected(ask("prism-sk-wrong-000000", "no-such-model", "hello"), 401, "authentication_error");
        assertRejected(chat(SEARCH, "{\"model\":\"fast\"}"), 400, "invalid_request_error");
        assertRejected(ask(SEARCH, "no-such-model", "hello"), 404, "not_found_error");
        // The allowlist: the search key may only use fast.
        assertRejected(ask(SEARCH, "smart", "hello"), 403, "model_not_allowed");
        assertRejected(ask(SEARCH, "auto", "hello"), 403, "model_not_allowed");
        // A provider's model name is not a way around the aliases.
        assertRejected(ask(RESEARCH, "alpha-large", "hello"), 403, "model_not_allowed");

        assertThat(logCount() - logsBefore).as("one log row per request, rejections included").isEqualTo(8);
        assertThat(ALPHA.requests() + BETA.requests()).as("rejected requests never reach a provider")
                .isEqualTo(upstreamBefore);
    }

    @Test
    @Order(2)
    void aServedRequestCarriesTheHeaderContractAndMatchesItsLogRow() throws Exception {
        int alphaBefore = ALPHA.requests();
        String question = "What is a message queue and when should I use one?";

        ResponseEntity<String> first = ask(SEARCH, "fast", question);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertHeaderContract(first, "alpha/alpha-small", "miss", false, "0.000001650");
        assertThat(mapper.readTree(first.getBody()).path("choices").isArray()).isTrue();
        assertThat(logRow(first))
                .containsEntry("status", "ok")
                .containsEntry("resolved_provider", "alpha")
                .containsEntry("resolved_model", "alpha-small")
                .containsEntry("prompt_tokens", 3)
                .containsEntry("completion_tokens", 2)
                .containsEntry("cost_nanos", ALPHA_SMALL_COST) // the same integer as the header
                .containsEntry("cache", "miss");

        // The same question again: served from the cache, free, without calling the provider.
        ResponseEntity<String> again = ask(SEARCH, "fast", question);
        assertHeaderContract(again, "cache", "hit", false, "0");
        assertThat(logRow(again)).containsEntry("cache", "hit").containsEntry("cost_nanos", 0L);
        assertThat(ALPHA.requests()).isEqualTo(alphaBefore + 1);
    }

    @Test
    @Order(3)
    void aStreamKeepsTheHeaderContractAndIsMeteredBeforeItsEnd() throws Exception {
        ResponseEntity<String> r = chat(RESEARCH, body("fast", "Explain how server-sent events work.", true));

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getFirst("Content-Type")).startsWith("text/event-stream");
        assertHeaderContract(r, "alpha/alpha-small", "miss", false, null); // the cost is unknown when headers go out
        // alpha-small, 3 prompt + 4 completion tokens: 3 x $0.15 + 4 x $0.60 per 1M = 2,850 nano-USD
        assertThat(r.getBody()).contains("data: {").contains(": prism-cost-usd=0.000002850")
                .endsWith("data: [DONE]\n\n");
        assertThat(logRow(r))
                .containsEntry("stream", true)
                .containsEntry("status", "ok")
                .containsEntry("completion_tokens", 4)
                .containsEntry("cost_nanos", 2_850L);
    }

    @Test
    @Order(4)
    void theRateLimitRejectsTheEleventhRequestOfTheMinute() throws Exception {
        for (int i = 1; i <= 10; i++) {
            assertThat(ask(FREE, "fast", "Rate limit probe " + i + ": what is TCP?").getStatusCode().value())
                    .isEqualTo(200);
        }
        ResponseEntity<String> eleventh = ask(FREE, "fast", "Rate limit probe 11: what is TCP?");
        assertRejected(eleventh, 429, "rate_limit_exceeded");
        assertThat(eleventh.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    @Order(5)
    void theBudgetAdmitsRequestsUntilItIsSpentThenRejectsAndLogsTheRejection() throws Exception {
        long budget = jdbc.queryForObject("SELECT monthly_budget_nanos FROM tenants WHERE team = 'budget-demo'", Long.class);
        ResponseEntity<String> r = null;
        int served = 0;
        for (int i = 0; i < 20; i++) {
            r = ask(BUDGET_DEMO, "fast", "What is a message queue and when should I use one?");
            if (r.getStatusCode().value() != 200) {
                break;
            }
            served++;
        }
        assertThat(served).isPositive();
        assertRejected(r, 402, "budget_exceeded");
        long spent = jdbc.queryForObject("""
                SELECT u.spent_nanos FROM usage_monthly u JOIN tenants t ON t.id = u.tenant_id
                WHERE t.team = 'budget-demo'""", Long.class);
        // A request is admitted only while spend is below the budget, so the overshoot is less than one request.
        assertThat(spent).isGreaterThanOrEqualTo(budget).isLessThan(budget + ALPHA_SMALL_COST);
    }

    @Test
    @Order(6)
    void autoSendsHardPromptsToSmartButOnlyToATierTheKeyMayUse() throws Exception {
        String proof = "Prove that the square root of 2 is irrational.";

        ResponseEntity<String> research = ask(RESEARCH, "auto", proof);
        assertThat(research.getHeaders().getFirst("x-prism-route")).startsWith("smart;");
        assertThat(research.getHeaders().getFirst("x-prism-provider")).isEqualTo("alpha/alpha-large");
        assertThat(logRow(research)).containsEntry("route_tier", "smart");

        insertKey("auto-and-fast", "prism-sk-test-autofast-1a1a", "auto,fast");
        ResponseEntity<String> limited = ask("prism-sk-test-autofast-1a1a", "auto", proof);
        assertThat(limited.getStatusCode().value()).isEqualTo(200);
        assertThat(limited.getHeaders().getFirst("x-prism-route")).startsWith("fast;");
        assertThat(limited.getHeaders().getFirst("x-prism-provider")).isEqualTo("alpha/alpha-small");
        assertThat((String) logRow(limited).get("route_reason")).contains("not on allowlist");

        insertKey("auto-alone", "prism-sk-test-autoonly-2b2b", "auto");
        assertRejected(ask("prism-sk-test-autoonly-2b2b", "auto", proof), 403, "model_not_allowed");
    }

    @Test
    @Order(7)
    void failsOverToBetaWhenAlphaIsDownAndReportsIt() throws Exception {
        ALPHA.answerOtherModelsAs("down");
        try {
            ResponseEntity<String> r = ask(RESEARCH, "fast", "Failover probe: what is a CDN?");
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertHeaderContract(r, "beta/beta-small", "miss", true, "0.000001100"); // 3 x $0.10 + 2 x $0.40 per 1M
            Map<String, Object> row = logRow(r);
            assertThat(row).containsEntry("fallback", true).containsEntry("resolved_provider", "beta");
            assertThat(row.get("attempts").toString()).contains("\"alpha\"").contains("http_503");

            BETA.answerOtherModelsAs("down");
            ResponseEntity<String> none = ask(RESEARCH, "fast", "Failover probe: what is DNS?");
            assertThat(none.getStatusCode().value()).isEqualTo(502);
            assertThat(mapper.readTree(none.getBody()).path("error").path("type").asText()).isEqualTo("upstream_error");
            assertHeaderContract(none, "none", "miss", false, "0");
            assertThat(logRow(none)).containsEntry("status", "upstream_error");
        } finally {
            ALPHA.answerOtherModelsAs("ok");
            BETA.answerOtherModelsAs("ok");
        }
    }

    // ------------------------------------------------------------------------------------------------

    private static FakeProvider startFake() {
        try {
            FakeProvider fake = new FakeProvider();
            fake.answerOtherModelsAs("ok");
            return fake;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ResponseEntity<String> ask(String key, String model, String prompt) {
        return chat(key, body(model, prompt, false));
    }

    private ResponseEntity<String> chat(String key, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            headers.setBearerAuth(key);
        }
        return http.exchange("/v1/chat/completions", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private String body(String model, String prompt, boolean stream) {
        ObjectNode body = mapper.createObjectNode().put("model", model).put("stream", stream);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        return body.toString();
    }

    private void assertRejected(ResponseEntity<String> r, int status, String type) throws Exception {
        assertThat(r.getStatusCode().value()).isEqualTo(status);
        assertThat(mapper.readTree(r.getBody()).path("error").path("type").asText()).isEqualTo(type);
        assertHeaderContract(r, "none", "miss", false, "0");
        Map<String, Object> row = logRow(r);
        assertThat(row).containsEntry("http_status", status).containsEntry("error_type", type);
        assertThat((String) row.get("status")).startsWith("rejected_");
    }

    private static void assertHeaderContract(ResponseEntity<String> r, String provider, String cache, boolean fallback,
                                             String costUsd) {
        HttpHeaders h = r.getHeaders();
        assertThat(h.getFirst("x-prism-provider")).isEqualTo(provider);
        assertThat(h.getFirst("x-prism-cache")).isEqualTo(cache);
        assertThat(h.getFirst("x-prism-fallback")).isEqualTo(Boolean.toString(fallback));
        assertThat(h.getFirst("x-prism-cost-usd")).isEqualTo(costUsd);
    }

    /** The request's log row, found by the x-request-id header. It is written before the response is released. */
    private Map<String, Object> logRow(ResponseEntity<String> r) {
        String requestId = r.getHeaders().getFirst("x-request-id");
        assertThat(requestId).isNotBlank();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM request_logs WHERE request_id = ?", requestId);
        assertThat(rows).as("log rows for %s", requestId).hasSize(1);
        return rows.getFirst();
    }

    private long logCount() {
        return jdbc.queryForObject("SELECT count(*) FROM request_logs", Long.class);
    }

    private void insertKey(String team, String key, String allowlist) {
        jdbc.update("""
                INSERT INTO tenants (team, key_hash, key_hint, monthly_budget_nanos, rpm_limit, model_allowlist)
                VALUES (?, ?, ?, 1000000000, 60, string_to_array(?, ','))""",
                team, Keys.sha256(key), Keys.hint(key), allowlist);
    }
}
