package com.prism.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.prism.common.Keys;
import com.prism.common.Money;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Key management: list, create, rotate, disable and enable virtual keys. A new or rotated key is
 * shown exactly once, in the response; only its hash is stored. The gateway caches key lookups for
 * five seconds, so changes take effect within that time.
 */
@RestController
public class KeysController {

    private static final Set<String> KNOWN_MODELS = Set.of("fast", "smart", "auto");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public KeysController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/admin/keys")
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT t.team, t.key_hint AS key, t.status, t.model_allowlist::text AS model_allowlist,
                       t.rpm_limit, t.tpm_limit, t.monthly_budget_nanos / 1e9 AS monthly_budget_usd,
                       coalesce(u.spent_nanos, 0) / 1e9 AS spent_this_month_usd,
                       t.cache_enabled, t.cache_threshold, t.created_at
                FROM tenants t
                LEFT JOIN usage_monthly u ON u.tenant_id = t.id
                     AND u.month = date_trunc('month', now() AT TIME ZONE 'UTC')::date
                ORDER BY t.team
                """);
    }

    @PostMapping("/admin/keys")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody JsonNode body) {
        String team = text(body, "team");
        if (team == null || !team.matches("[a-z0-9-]{2,40}")) {
            throw bad("'team' is required: 2-40 lowercase letters, digits or dashes");
        }
        List<String> allowlist = new ArrayList<>();
        body.path("model_allowlist").forEach(m -> allowlist.add(m.asText()));
        if (allowlist.isEmpty()) {
            allowlist.add("fast");
        }
        if (!KNOWN_MODELS.containsAll(allowlist)) {
            throw bad("model_allowlist may only contain " + KNOWN_MODELS);
        }
        double budget = body.path("monthly_budget_usd").asDouble(10);
        int rpm = body.path("requests_per_minute").asInt(60);
        boolean cache = body.path("semantic_cache").path("enabled").asBoolean(true);
        double threshold = body.path("semantic_cache").path("similarity_threshold").asDouble(0.92);
        if (budget < 0 || rpm < 1 || threshold <= 0 || threshold > 1) {
            throw bad("budget must be >= 0, requests_per_minute >= 1, 0 < similarity_threshold <= 1");
        }
        String key = newKey(team);
        int inserted = jdbc.update("""
                INSERT INTO tenants (team, key_hash, key_hint, monthly_budget_nanos, rpm_limit, model_allowlist,
                                     cache_enabled, cache_threshold)
                VALUES (?, ?, ?, ?, ?, string_to_array(?, ','), ?, ?)
                ON CONFLICT DO NOTHING
                """, team, Keys.sha256(key), Keys.hint(key), Money.usdToNanos(java.math.BigDecimal.valueOf(budget)),
                rpm, String.join(",", allowlist), cache, cache ? threshold : null);
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "team '" + team + "' already exists");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team", team);
        out.put("virtual_key", key);
        out.put("note", "Store this key now: it is shown only once.");
        return out;
    }

    @PostMapping("/admin/keys/{team}/rotate")
    public Map<String, Object> rotate(@PathVariable String team) {
        String key = newKey(team);
        int updated = jdbc.update("UPDATE tenants SET key_hash = ?, key_hint = ? WHERE team = ?",
                Keys.sha256(key), Keys.hint(key), team);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such team");
        }
        return Map.of("team", team, "virtual_key", key,
                "note", "The previous key stops working within 5 seconds. Store this key now: it is shown only once.");
    }

    @PostMapping("/admin/keys/{team}/disable")
    public Map<String, Object> disable(@PathVariable String team) {
        return setStatus(team, "disabled");
    }

    @PostMapping("/admin/keys/{team}/enable")
    public Map<String, Object> enable(@PathVariable String team) {
        return setStatus(team, "active");
    }

    private Map<String, Object> setStatus(String team, String status) {
        if (jdbc.update("UPDATE tenants SET status = ? WHERE team = ?", status, team) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such team");
        }
        return Map.of("team", team, "status", status);
    }

    private static String newKey(String team) {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return "prism-sk-" + team + "-" + HexFormat.of().formatHex(bytes);
    }

    private static String text(JsonNode body, String field) {
        return body.path(field).isTextual() ? body.get(field).asText().trim() : null;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
