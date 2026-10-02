package com.prism.gateway.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.embedding.EmbeddingService;
import com.prism.gateway.tenant.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Per-tenant semantic response cache in Postgres + pgvector.
 * <p>
 * Lookup is two-stage: first an exact match on the normalised prompt hash (cheap, no embedding
 * needed), then a nearest-neighbour search on the embedding restricted to the same tenant and
 * scope. A candidate is a hit only if its cosine similarity reaches the key's threshold <i>and</i>
 * it passes the entity, polarity and order guards. Every query filters on tenant_id, so one team can never receive
 * another team's answer.
 * <p>
 * Eviction: entries expire after the TTL, expired rows are purged every few minutes, and each
 * tenant is capped at a maximum number of entries (least recently used go first).
 */
@Component
public class SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(SemanticCache.class);
    private static final double DEFAULT_THRESHOLD = 0.90;

    public record Hit(long entryId, JsonNode response, String servedBy, double similarity, boolean exact) {
    }

    /** hit is null on a miss; bestSimilarity and note explain near misses in the request log. */
    public record Lookup(Hit hit, Double bestSimilarity, String note) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PrismProperties.Cache settings;

    public SemanticCache(JdbcTemplate jdbc, ObjectMapper mapper, PrismProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.settings = props.cache();
    }

    public Lookup lookup(Tenant tenant, PromptKey key, Supplier<float[]> embedding) {
        List<Hit> exact = jdbc.query("""
                SELECT id, response::text AS response, served_by FROM cache_entries
                WHERE tenant_id = ? AND scope_key = ? AND prompt_hash = ? AND expires_at > now()
                """, (rs, i) -> new Hit(rs.getLong("id"), json(rs.getString("response")), rs.getString("served_by"), 1.0, true),
                tenant.id(), key.scopeKey(), key.promptHash());
        if (!exact.isEmpty()) {
            touch(exact.getFirst().entryId());
            return new Lookup(exact.getFirst(), 1.0, "exact match");
        }
        if (!key.semanticEligible()) {
            return new Lookup(null, null, "exact-match only (prompt longer than " + settings.maxSemanticWords() + " words)");
        }

        double threshold = tenant.cacheThreshold() != null ? tenant.cacheThreshold() : DEFAULT_THRESHOLD;
        String vector = EmbeddingService.toPgVector(embedding.get());
        record Candidate(long id, String promptText, String response, String servedBy, double similarity) {
        }
        List<Candidate> candidates = jdbc.query("""
                SELECT id, prompt_text, response::text AS response, served_by,
                       1 - (embedding <=> ?::vector) AS similarity
                FROM cache_entries
                WHERE tenant_id = ? AND scope_key = ? AND expires_at > now() AND embedding IS NOT NULL
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """, (rs, i) -> new Candidate(rs.getLong("id"), rs.getString("prompt_text"), rs.getString("response"),
                        rs.getString("served_by"), rs.getDouble("similarity")),
                vector, tenant.id(), key.scopeKey(), vector, settings.candidates());

        if (candidates.isEmpty()) {
            return new Lookup(null, null, "no entries in scope");
        }
        String note = null; // why the best candidate was rejected: the one a reader of the log cares about
        for (Candidate c : candidates) {
            if (c.similarity() < threshold) {
                break; // ordered by similarity: nothing further can pass
            }
            if (!CachePolicy.entitiesCompatible(key.text(), c.promptText())) {
                if (note == null) {
                    note = String.format(Locale.ROOT, "entity guard rejected a %.3f match (%s vs %s)", c.similarity(),
                            CachePolicy.entities(key.text()), CachePolicy.entities(c.promptText()));
                }
                continue;
            }
            String guard = "polarity";
            String conflict = CachePolicy.polarityConflict(key.text(), c.promptText());
            if (conflict == null) {
                guard = "order";
                conflict = CachePolicy.orderConflict(key.text(), c.promptText());
            }
            if (conflict != null) {
                if (note == null) {
                    note = String.format(Locale.ROOT, "%s guard rejected a %.3f match (%s)", guard, c.similarity(), conflict);
                }
                continue;
            }
            touch(c.id());
            return new Lookup(new Hit(c.id(), json(c.response()), c.servedBy(), c.similarity(), false), c.similarity(),
                    String.format(Locale.ROOT, "semantic match %.3f >= %.2f", c.similarity(), threshold));
        }
        double best = candidates.getFirst().similarity();
        return new Lookup(null, best, note != null ? note
                : String.format(Locale.ROOT, "best similarity %.3f < threshold %.2f", best, threshold));
    }

    public void store(Tenant tenant, PromptKey key, float[] embeddingOrNull, JsonNode completion, String servedBy, long costNanos) {
        jdbc.update("""
                INSERT INTO cache_entries (tenant_id, scope_key, prompt_hash, prompt_text, guard_tokens, embedding,
                                           response, served_by, cost_nanos, expires_at)
                VALUES (?, ?, ?, ?, ?, ?::vector, ?::jsonb, ?, ?, now() + (? * interval '1 second'))
                ON CONFLICT (tenant_id, scope_key, prompt_hash) DO UPDATE
                SET prompt_text = EXCLUDED.prompt_text, guard_tokens = EXCLUDED.guard_tokens,
                    embedding = EXCLUDED.embedding, response = EXCLUDED.response, served_by = EXCLUDED.served_by,
                    cost_nanos = EXCLUDED.cost_nanos, hit_count = 0, created_at = now(), last_hit_at = NULL,
                    expires_at = EXCLUDED.expires_at
                WHERE cache_entries.expires_at <= now() -- a live entry stays; an expired one awaiting the purge is replaced
                """,
                tenant.id(), key.scopeKey(), key.promptHash(), key.text(), String.join(" ", CachePolicy.entities(key.text())),
                embeddingOrNull == null ? null : EmbeddingService.toPgVector(embeddingOrNull),
                completion.toString(), servedBy, costNanos, settings.ttl().toSeconds());
    }

    private void touch(long entryId) {
        jdbc.update("UPDATE cache_entries SET hit_count = hit_count + 1, last_hit_at = now() WHERE id = ?", entryId);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    void evict() {
        int expired = jdbc.update("DELETE FROM cache_entries WHERE expires_at <= now()");
        int overCap = jdbc.update("""
                DELETE FROM cache_entries WHERE id IN (
                  SELECT id FROM (
                    SELECT id, row_number() OVER (PARTITION BY tenant_id
                                                  ORDER BY coalesce(last_hit_at, created_at) DESC) AS rn
                    FROM cache_entries) ranked
                  WHERE rn > ?)
                """, settings.maxEntriesPerTenant());
        if (expired + overCap > 0) {
            log.info("Cache eviction: {} expired, {} over the per-tenant cap", expired, overCap);
        }
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (IOException e) {
            throw new IllegalStateException("corrupt cache entry", e);
        }
    }
}
