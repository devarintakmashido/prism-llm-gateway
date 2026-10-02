package com.prism.gateway;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.common.seed.SeedLoader;
import com.prism.gateway.cache.PromptKey;
import com.prism.gateway.cache.SemanticCache;
import com.prism.gateway.core.PrismError;
import com.prism.gateway.core.PrismException;
import com.prism.gateway.embedding.EmbeddingService;
import com.prism.gateway.limits.BudgetService;
import com.prism.gateway.limits.RateLimiter;
import com.prism.gateway.tenant.Tenant;
import com.prism.gateway.tenant.TenantService;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The correctness guarantees that only show up against real infrastructure and real concurrency:
 * no over-admission (rate limit and budget), exact accounting, and no cross-tenant cache hits.
 */
@Testcontainers
class ConcurrencyAndIsolationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static TenantService tenants;
    static LettuceConnectionFactory redisFactory;

    @BeforeAll
    static void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(40);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        new SeedLoader(jdbc, TestFixtures.MAPPER).load(TestFixtures.DATA_DIR);
        tenants = new TenantService(jdbc);

        redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        redisFactory.start();
    }

    @AfterAll
    static void tearDown() {
        redisFactory.destroy();
        dataSource.close();
    }

    private static <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(task));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    @Test
    void seedDataIsLoadedAndIdempotent() {
        SeedLoader.Summary again = new SeedLoader(jdbc, TestFixtures.MAPPER).load(TestFixtures.DATA_DIR);
        assertThat(again.tenantsInserted()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants", Integer.class)).isEqualTo(4);
        // keys are stored hashed; the plain key never reaches the database
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants WHERE key_hash LIKE 'prism-sk-%'", Integer.class)).isZero();
        assertThat(tenants.authenticate("Bearer prism-sk-search-1a2b3c").team()).isEqualTo("search");
    }

    @Test
    void rateLimiterNeverOverAdmitsUnderConcurrency() throws Exception {
        RateLimiter limiter = new RateLimiter(new StringRedisTemplate(redisFactory));
        Tenant free = tenants.authenticate("Bearer prism-sk-free-7g8h9i"); // 10 requests per minute
        AtomicInteger ids = new AtomicInteger();
        List<Boolean> decisions = concurrently(50, () -> limiter.acquire(free, "req-" + ids.incrementAndGet()).allowed());
        assertThat(decisions.stream().filter(Boolean::booleanValue).count()).isEqualTo(10);
    }

    @Test
    void budgetAdmitsExactlyOneRequestForTheTinyBudgetKeyEvenUnderConcurrency() throws Exception {
        BudgetService budget = new BudgetService(jdbc);
        Tenant demo = tenants.authenticate("Bearer prism-sk-budget-demo-0j1k2l"); // $0.00001
        long estimate = 155_250; // a normal request's worst-case estimate, far above the budget
        List<BudgetService.Reservation> admitted = concurrently(20, () -> {
            try {
                return budget.reserve(demo, estimate);
            } catch (PrismException e) {
                assertThat(e.error()).isEqualTo(PrismError.BUDGET_EXCEEDED);
                return null;
            }
        });
        List<BudgetService.Reservation> ok = admitted.stream().filter(r -> r != null).toList();
        assertThat(ok).hasSize(1);

        budget.settle(ok.getFirst(), 16_050, 11, 24, true);
        PrismException second = null;
        try {
            budget.reserve(demo, estimate);
        } catch (PrismException e) {
            second = e;
        }
        assertThat(second).isNotNull();
        assertThat(second.error()).isEqualTo(PrismError.BUDGET_EXCEEDED);
        assertThat(jdbc.queryForObject("SELECT reserved_nanos FROM usage_monthly WHERE tenant_id = ?", Long.class, demo.id()))
                .isZero(); // no leaked reservations
    }

    @Test
    void firstRequestsOfAMonthNeverFalselyRejectEachOther() throws Exception {
        // Regression: on an empty month the first concurrent requests race to create the usage row.
        // A losing request must retry, not report "budget exhausted" with $0 spent.
        BudgetService budget = new BudgetService(jdbc);
        Tenant search = tenants.authenticate("Bearer prism-sk-search-1a2b3c"); // $50 budget
        jdbc.update("DELETE FROM usage_monthly WHERE tenant_id = ?", search.id());
        List<Boolean> admitted = concurrently(30, () -> {
            try {
                budget.reserve(search, 155_250);
                return true;
            } catch (PrismException e) {
                return false;
            }
        });
        assertThat(admitted).containsOnly(true);
    }

    @Test
    void concurrentSpendIsCountedExactly() throws Exception {
        BudgetService budget = new BudgetService(jdbc);
        Tenant research = tenants.authenticate("Bearer prism-sk-research-4d5e6f");
        concurrently(40, () -> {
            BudgetService.Reservation r = budget.reserve(research, 1_000_000);
            budget.settle(r, 12_345, 10, 20, true);
            return null;
        });
        Long spent = jdbc.queryForObject("SELECT spent_nanos FROM usage_monthly WHERE tenant_id = ?", Long.class, research.id());
        Long reserved = jdbc.queryForObject("SELECT reserved_nanos FROM usage_monthly WHERE tenant_id = ?", Long.class, research.id());
        assertThat(spent).isEqualTo(40 * 12_345L); // no lost updates
        assertThat(reserved).isZero();
    }

    @Test
    void semanticCacheHitsParaphrasesButNeverAcrossTenants() {
        EmbeddingService embeddings = new EmbeddingService();
        SemanticCache cache = new SemanticCache(jdbc, TestFixtures.MAPPER, TestFixtures.props());
        Tenant search = tenants.authenticate("Bearer prism-sk-search-1a2b3c");   // threshold 0.92
        Tenant free = tenants.authenticate("Bearer prism-sk-free-7g8h9i");       // threshold 0.85

        PromptKey a1 = key(search, "How do I reset my password on the dashboard?");
        ObjectNode answer = TestFixtures.MAPPER.createObjectNode().put("object", "chat.completion");
        answer.putArray("choices").addObject().putObject("message").put("content", "Go to Settings > Security.");
        cache.store(search, a1, embeddings.embed(a1.text()), answer, "alpha/alpha-small", 16_050);

        PromptKey a2 = key(search, "What are the steps to reset my dashboard password?");
        SemanticCache.Lookup paraphrase = cache.lookup(search, a2, () -> embeddings.embed(a2.text()));
        assertThat(paraphrase.hit()).isNotNull();
        assertThat(paraphrase.hit().similarity()).isGreaterThanOrEqualTo(0.92);

        PromptKey a3 = key(search, "How do I reset my two-factor authentication on the dashboard?");
        assertThat(cache.lookup(search, a3, () -> embeddings.embed(a3.text())).hit()).isNull();

        // The identical question from another team must never see the search team's answer.
        PromptKey sameForFree = key(free, "How do I reset my password on the dashboard?");
        assertThat(cache.lookup(free, sameForFree, () -> embeddings.embed(sameForFree.text())).hit()).isNull();
    }

    @Test
    void semanticCacheNeverAnswersTheOppositeQuestion() {
        EmbeddingService embeddings = new EmbeddingService();
        SemanticCache cache = new SemanticCache(jdbc, TestFixtures.MAPPER, TestFixtures.props());
        Tenant search = tenants.authenticate("Bearer prism-sk-search-1a2b3c");   // threshold 0.92
        ObjectNode answer = TestFixtures.MAPPER.createObjectNode().put("object", "chat.completion");
        answer.putArray("choices").addObject().putObject("message").put("content", "Kafka gives you a durable log.");

        PromptKey use = key(search, "Why should I use Kafka for event streaming?");
        cache.store(search, use, embeddings.embed(use.text()), answer, "alpha/alpha-small", 16_050);

        // Above the threshold by cosine (0.986), so only the polarity guard stands between the caller
        // and the wrong answer.
        PromptKey dontUse = key(search, "Why should I not use Kafka for event streaming?");
        SemanticCache.Lookup lookup = cache.lookup(search, dontUse, () -> embeddings.embed(dontUse.text()));
        assertThat(lookup.hit()).isNull();
        assertThat(lookup.bestSimilarity()).isGreaterThan(0.92);
        assertThat(lookup.note()).startsWith("polarity guard rejected");
    }

    @Test
    void semanticCacheNeverAnswersTheReversedQuestion() {
        EmbeddingService embeddings = new EmbeddingService();
        SemanticCache cache = new SemanticCache(jdbc, TestFixtures.MAPPER, TestFixtures.props());
        Tenant search = tenants.authenticate("Bearer prism-sk-search-1a2b3c");   // threshold 0.92

        PromptKey cToF = key(search, "How do I convert Celsius to Fahrenheit?");
        cache.store(search, cToF, embeddings.embed(cToF.text()), answer("Multiply by 9/5, then add 32."), "alpha/alpha-small", 1);

        PromptKey fToC = key(search, "How do I convert Fahrenheit to Celsius?");
        SemanticCache.Lookup lookup = cache.lookup(search, fToC, () -> embeddings.embed(fToC.text()));
        assertThat(lookup.hit()).isNull();
        assertThat(lookup.bestSimilarity()).isGreaterThan(0.92); // cosine alone would have served the C-to-F answer
        assertThat(lookup.note()).startsWith("order guard rejected");
    }

    @Test
    void anExpiredEntryIsReplacedWithoutWaitingForThePurgeButALiveOneIsKept() {
        EmbeddingService embeddings = new EmbeddingService();
        SemanticCache cache = new SemanticCache(jdbc, TestFixtures.MAPPER, TestFixtures.props());
        Tenant search = tenants.authenticate("Bearer prism-sk-search-1a2b3c");
        PromptKey k = key(search, "How do I renew an expired TLS certificate?");

        cache.store(search, k, embeddings.embed(k.text()), answer("Run certbot renew."), "alpha/alpha-small", 1);
        jdbc.update("UPDATE cache_entries SET expires_at = now() - interval '1 second' WHERE prompt_hash = ?", k.promptHash());
        assertThat(cache.lookup(search, k, () -> embeddings.embed(k.text())).hit()).isNull(); // expired, not yet purged

        cache.store(search, k, embeddings.embed(k.text()), answer("Use your CA's renewal API."), "beta/beta-small", 1);
        cache.store(search, k, embeddings.embed(k.text()), answer("A third answer."), "alpha/alpha-small", 1);
        SemanticCache.Hit hit = cache.lookup(search, k, () -> embeddings.embed(k.text())).hit();
        assertThat(hit).isNotNull();
        // The expired entry was replaced; the live replacement then kept its place.
        assertThat(hit.response().path("choices").path(0).path("message").path("content").asText())
                .isEqualTo("Use your CA's renewal API.");
    }

    private static ObjectNode answer(String content) {
        ObjectNode answer = TestFixtures.MAPPER.createObjectNode().put("object", "chat.completion");
        answer.putArray("choices").addObject().putObject("message").put("content", content);
        return answer;
    }

    private static PromptKey key(Tenant tenant, String prompt) {
        return PromptKey.of("fast", TestFixtures.userMessage("fast", prompt), TestFixtures.MAPPER, 150);
    }
}
