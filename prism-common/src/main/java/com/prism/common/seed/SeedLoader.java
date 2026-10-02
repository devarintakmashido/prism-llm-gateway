package com.prism.common.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.common.Keys;
import com.prism.common.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loads the provided seed data (data/seed_keys.json, data/model_pricing.json) into Postgres.
 * Safe to run on every start, and from several services at once: tenants are inserted once and
 * never overwritten (so a key disabled at runtime stays disabled), while prices are upserted
 * because the file is the source of truth for pricing.
 */
public class SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SeedLoader(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Summary(int tenantsInserted, int tenantsInFile, int pricesUpserted) {
    }

    public Summary load(Path dataDir) {
        int prices = loadPrices(dataDir.resolve("model_pricing.json"));
        int[] tenants = loadTenants(dataDir.resolve("seed_keys.json"));
        Summary summary = new Summary(tenants[0], tenants[1], prices);
        log.info("Seed data loaded from {}: {} prices, {}/{} tenants inserted", dataDir, prices, tenants[0], tenants[1]);
        return summary;
    }

    private int loadPrices(Path file) {
        JsonNode root = read(file);
        int count = 0;
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (entry.getKey().startsWith("_")) {
                continue; // "_comment"
            }
            JsonNode price = entry.getValue();
            jdbc.update("""
                    INSERT INTO model_prices (model, input_per_1m, output_per_1m) VALUES (?, ?, ?)
                    ON CONFLICT (model) DO UPDATE
                    SET input_per_1m = EXCLUDED.input_per_1m, output_per_1m = EXCLUDED.output_per_1m, updated_at = now()
                    """, entry.getKey(), price.get("input_per_1m").decimalValue(), price.get("output_per_1m").decimalValue());
            count++;
        }
        return count;
    }

    private int[] loadTenants(Path file) {
        JsonNode tenants = read(file).path("tenants");
        int inserted = 0;
        for (JsonNode t : tenants) {
            String key = t.get("virtual_key").asText();
            List<String> allowlist = new ArrayList<>();
            t.get("model_allowlist").forEach(m -> allowlist.add(m.asText()));
            JsonNode cache = t.path("semantic_cache");
            boolean cacheEnabled = cache.path("enabled").asBoolean(false);
            Double threshold = cache.has("similarity_threshold") ? cache.get("similarity_threshold").asDouble() : null;
            JsonNode rate = t.path("rate_limit");
            Integer tpm = rate.has("tokens_per_minute") ? rate.get("tokens_per_minute").asInt() : null;
            BigDecimal budget = t.get("monthly_budget_usd").decimalValue();

            inserted += jdbc.update("""
                    INSERT INTO tenants (team, key_hash, key_hint, monthly_budget_nanos, rpm_limit, tpm_limit,
                                         model_allowlist, cache_enabled, cache_threshold)
                    VALUES (?, ?, ?, ?, ?, ?, string_to_array(?, ','), ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                    t.get("team").asText(), Keys.sha256(key), Keys.hint(key), Money.usdToNanos(budget),
                    rate.get("requests_per_minute").asInt(), tpm, String.join(",", allowlist), cacheEnabled, threshold);
        }
        return new int[]{inserted, tenants.size()};
    }

    private JsonNode read(Path file) {
        try {
            return mapper.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read seed file " + file.toAbsolutePath(), e);
        }
    }
}
