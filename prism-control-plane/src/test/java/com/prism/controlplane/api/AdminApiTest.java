package com.prism.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The control plane's HTTP surface against real Postgres (pgvector) and Redis: the admin token guard,
 * key management, and usage numbers that must reconcile with the request log.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiTest {

    private static final String ADMIN = "test-admin-token";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("prism.admin-token", () -> ADMIN);
        registry.add("prism.data-dir", () -> java.nio.file.Path.of("..", "data").toAbsolutePath().normalize().toString());
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    private ResponseEntity<String> call(HttpMethod method, String path, String token, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path) {
        return call(HttpMethod.GET, path, ADMIN, null);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return mapper.readTree(response.getBody());
    }

    @Test
    void adminEndpointsRequireTheAdminToken() {
        assertThat(call(HttpMethod.GET, "/admin/keys", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.GET, "/admin/keys", "wrong-token", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(HttpMethod.POST, "/admin/keys", null, Map.of("team", "sneaky")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/admin/keys").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void consolePageIsPublicButItsApiIsNot() {
        ResponseEntity<String> page = http.getForEntity("/", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("Prism");
        assertThat(http.getForEntity("/admin/overview", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void seededKeysAreListedWithoutTheirSecrets() throws Exception {
        JsonNode keys = json(get("/admin/keys"));
        assertThat(keys).hasSizeGreaterThanOrEqualTo(4);
        assertThat(keys.toString()).contains("search", "free-tier", "budget-demo").doesNotContain("prism-sk-search-1a2b3c");
    }

    @Test
    void newKeyIsShownOnceAndStoredOnlyAsAHash() throws Exception {
        ResponseEntity<String> created = call(HttpMethod.POST, "/admin/keys", ADMIN,
                Map.of("team", "created-by-test", "model_allowlist", new String[]{"fast"}, "monthly_budget_usd", 5));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String key = json(created).get("virtual_key").asText();
        assertThat(key).startsWith("prism-sk-created-by-test-");

        String stored = jdbc.queryForObject("SELECT key_hash FROM tenants WHERE team = 'created-by-test'", String.class);
        assertThat(stored).hasSize(64).isNotEqualTo(key);
        assertThat(get("/admin/keys").getBody()).doesNotContain(key);

        // a key can be used to look up its own usage, and an unknown key is a 404
        assertThat(get("/admin/usage?key=" + key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/admin/usage?key=prism-sk-nope").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createValidatesInputAndRejectsDuplicates() {
        assertThat(call(HttpMethod.POST, "/admin/keys", ADMIN, Map.of("team", "Bad Name!")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/admin/keys", ADMIN,
                Map.of("team", "bad-model", "model_allowlist", new String[]{"gpt-9"})).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(call(HttpMethod.POST, "/admin/keys", ADMIN,
                Map.of("team", "bad-rpm", "requests_per_minute", 0)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(call(HttpMethod.POST, "/admin/keys", ADMIN, Map.of("team", "dupe-team")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(call(HttpMethod.POST, "/admin/keys", ADMIN, Map.of("team", "dupe-team")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rotateInvalidatesTheOldKeyAndDisableEnableToggleStatus() throws Exception {
        String original = json(call(HttpMethod.POST, "/admin/keys", ADMIN, Map.of("team", "rotate-me"))).get("virtual_key").asText();
        String oldHash = jdbc.queryForObject("SELECT key_hash FROM tenants WHERE team = 'rotate-me'", String.class);

        String rotated = json(call(HttpMethod.POST, "/admin/keys/rotate-me/rotate", ADMIN, null)).get("virtual_key").asText();
        assertThat(rotated).isNotEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT key_hash FROM tenants WHERE team = 'rotate-me'", String.class)).isNotEqualTo(oldHash);
        assertThat(get("/admin/usage?key=" + original).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/admin/usage?key=" + rotated).getStatusCode()).isEqualTo(HttpStatus.OK);

        call(HttpMethod.POST, "/admin/keys/rotate-me/disable", ADMIN, null);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE team = 'rotate-me'", String.class)).isEqualTo("disabled");
        call(HttpMethod.POST, "/admin/keys/rotate-me/enable", ADMIN, null);
        assertThat(jdbc.queryForObject("SELECT status FROM tenants WHERE team = 'rotate-me'", String.class)).isEqualTo("active");

        assertThat(call(HttpMethod.POST, "/admin/keys/no-such-team/rotate", ADMIN, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(HttpMethod.POST, "/admin/keys/no-such-team/disable", ADMIN, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void usageIsComputedFromTheRequestLogAndFiltersByTimeWindow() throws Exception {
        call(HttpMethod.POST, "/admin/keys", ADMIN, Map.of("team", "usage-team", "monthly_budget_usd", 1));
        long id = jdbc.queryForObject("SELECT id FROM tenants WHERE team = 'usage-team'", Long.class);
        // two served requests (3 + 4 micro-dollars), one rate-limited rejection, one old request outside the window
        insertLog(id, "u-1", "ok", 200, 100, 50, 3_000, "miss", "now()");
        insertLog(id, "u-2", "ok", 200, 10, 5, 4_000, "hit", "now()");
        insertLog(id, "u-3", "rejected_rate_limit", 429, 0, 0, 0, "miss", "now()");
        insertLog(id, "u-old", "ok", 200, 999, 999, 999_000, "miss", "now() - interval '40 days'");

        JsonNode usage = json(get("/admin/usage?team=usage-team&from=" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).withDayOfMonth(1)));
        assertThat(usage.get("requests").asInt()).isEqualTo(3);
        assertThat(usage.get("served").asInt()).isEqualTo(2);
        assertThat(usage.get("rejected").asInt()).isEqualTo(1);
        assertThat(usage.get("cache_hits").asInt()).isEqualTo(1);
        assertThat(usage.get("prompt_tokens").asInt()).isEqualTo(110);
        assertThat(usage.get("completion_tokens").asInt()).isEqualTo(55);
        assertThat(usage.get("cost_usd").decimalValue()).isEqualByComparingTo("0.000007");
        assertThat(usage.get("budget").get("monthly_budget_usd").decimalValue()).isEqualByComparingTo("1");

        JsonNode logs = json(get("/admin/logs?team=usage-team&status=rejected_rate_limit"));
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).get("request_id").asText()).isEqualTo("u-3");

        assertThat(get("/admin/usage?team=usage-team&from=2026-09-10&to=2026-09-01").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(get("/admin/usage?team=usage-team&from=not-a-date").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void overviewCacheStatsAndProviderHealthRespondOnAnEmptyFleet() throws Exception {
        assertThat(get("/admin/overview").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/admin/cache/stats").getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode health = json(get("/admin/providers/health"));
        assertThat(health.get("gateway_instances")).isEmpty();
        assertThat(health.get("providers")).isEmpty();
    }

    private void insertLog(long tenantId, String requestId, String status, int httpStatus, int promptTokens,
                           int completionTokens, long costNanos, String cache, String createdAtSql) {
        jdbc.update("INSERT INTO request_logs (request_id, tenant_id, team, status, http_status, prompt_tokens, "
                        + "completion_tokens, cost_nanos, cache, latency_ms, created_at) "
                        + "VALUES (?, ?, 'usage-team', ?, ?, ?, ?, ?, ?, 5, " + createdAtSql + ")",
                requestId, tenantId, status, httpStatus, promptTokens, completionTokens, costNanos, cache);
    }
}
