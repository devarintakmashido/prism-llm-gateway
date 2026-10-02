package com.prism.controlplane.api;

import com.prism.common.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spend and token usage per key, computed from the request log (the same rows the gateway writes
 * together with the budget update), so these totals reconcile exactly with the sum of the
 * x-prism-cost-usd headers the caller received.
 */
@RestController
public class UsageController {

    private static final String TOTALS = """
            SELECT count(*)                                            AS requests,
                   count(*) FILTER (WHERE http_status = 200)           AS served,
                   count(*) FILTER (WHERE status LIKE 'rejected_%')    AS rejected,
                   count(*) FILTER (WHERE status IN ('upstream_error', 'upstream_timeout', 'stream_error')) AS upstream_failures,
                   coalesce(sum(prompt_tokens), 0)                     AS prompt_tokens,
                   coalesce(sum(completion_tokens), 0)                 AS completion_tokens,
                   coalesce(sum(cost_nanos), 0)                        AS cost_nanos,
                   count(*) FILTER (WHERE cache = 'hit')               AS cache_hits,
                   count(*) FILTER (WHERE fallback)                    AS fallbacks
            FROM request_logs
            WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
            """;

    private final JdbcTemplate jdbc;
    private final Tenants tenants;

    public UsageController(JdbcTemplate jdbc, Tenants tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    /** GET /admin/usage?key=...&from=...&to=...  (or ?team=...). Without key/team: one entry per key. */
    @GetMapping("/admin/usage")
    public Object usage(@RequestParam(required = false) String key, @RequestParam(required = false) String team,
                        @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        Window window = Window.parse(from, to);
        Map<String, Object> tenant = tenants.find(key, team);
        if (tenant != null) {
            return usageFor(tenant, window, true);
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> t : jdbc.queryForList("SELECT * FROM tenants ORDER BY team")) {
            all.add(usageFor(t, window, false));
        }
        return Map.of("from", window.from().toString(), "to", window.to().toString(), "keys", all);
    }

    private Map<String, Object> usageFor(Map<String, Object> tenant, Window window, boolean detailed) {
        long tenantId = ((Number) tenant.get("id")).longValue();
        Map<String, Object> totals = jdbc.queryForMap(TOTALS, tenantId, window.fromTs(), window.toTs());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team", tenant.get("team"));
        out.put("key", tenant.get("key_hint"));
        out.put("from", window.from().toString());
        out.put("to", window.to().toString());
        out.put("requests", totals.get("requests"));
        out.put("served", totals.get("served"));
        out.put("rejected", totals.get("rejected"));
        out.put("upstream_failures", totals.get("upstream_failures"));
        out.put("prompt_tokens", totals.get("prompt_tokens"));
        out.put("completion_tokens", totals.get("completion_tokens"));
        out.put("cost_usd", Money.nanosToUsd(((Number) totals.get("cost_nanos")).longValue()));
        out.put("cache_hits", totals.get("cache_hits"));
        out.put("fallbacks", totals.get("fallbacks"));
        out.put("budget", budget(tenant));
        if (detailed) {
            out.put("by_model", jdbc.queryForList("""
                    SELECT coalesce(resolved_alias, requested_model) AS alias, resolved_provider AS provider,
                           resolved_model AS model, count(*) AS requests,
                           sum(prompt_tokens) AS prompt_tokens, sum(completion_tokens) AS completion_tokens,
                           sum(cost_nanos) / 1e9 AS cost_usd
                    FROM request_logs
                    WHERE tenant_id = ? AND created_at >= ? AND created_at < ? AND http_status = 200
                    GROUP BY 1, 2, 3 ORDER BY cost_usd DESC
                    """, tenantId, window.fromTs(), window.toTs()));
            out.put("by_status", jdbc.queryForList("""
                    SELECT status, count(*) AS requests FROM request_logs
                    WHERE tenant_id = ? AND created_at >= ? AND created_at < ?
                    GROUP BY status ORDER BY requests DESC
                    """, tenantId, window.fromTs(), window.toTs()));
        }
        return out;
    }

    private Map<String, Object> budget(Map<String, Object> tenant) {
        long tenantId = ((Number) tenant.get("id")).longValue();
        long budgetNanos = ((Number) tenant.get("monthly_budget_nanos")).longValue();
        LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT spent_nanos, reserved_nanos FROM usage_monthly WHERE tenant_id = ? AND month = ?", tenantId, month);
        long spent = rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("spent_nanos")).longValue();
        long reserved = rows.isEmpty() ? 0 : ((Number) rows.getFirst().get("reserved_nanos")).longValue();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("month", month.toString().substring(0, 7));
        b.put("monthly_budget_usd", Money.nanosToUsd(budgetNanos));
        b.put("spent_usd", Money.nanosToUsd(spent));
        b.put("in_flight_reserved_usd", Money.nanosToUsd(reserved));
        b.put("remaining_usd", Money.nanosToUsd(Math.max(0, budgetNanos - spent)));
        b.put("exhausted", spent >= budgetNanos);
        return b;
    }

    /** Requests, spend and cache hits per UTC day, for the console chart. */
    @GetMapping("/admin/usage/daily")
    public List<Map<String, Object>> daily(@RequestParam(required = false) String key,
                                           @RequestParam(required = false) String team,
                                           @RequestParam(defaultValue = "14") int days) {
        Map<String, Object> tenant = tenants.find(key, team);
        String sql = """
                SELECT (created_at AT TIME ZONE 'UTC')::date AS day, count(*) AS requests,
                       sum(cost_nanos) / 1e9 AS cost_usd, count(*) FILTER (WHERE cache = 'hit') AS cache_hits,
                       count(*) FILTER (WHERE status LIKE 'rejected_%') AS rejected
                FROM request_logs WHERE created_at >= now() - (? * interval '1 day') %s
                GROUP BY 1 ORDER BY 1
                """;
        if (tenant == null) {
            return jdbc.queryForList(sql.formatted(""), days);
        }
        return jdbc.queryForList(sql.formatted("AND tenant_id = ?"), days, tenant.get("id"));
    }

    /** Headline numbers for the console (last 24 hours, all keys). */
    @GetMapping("/admin/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT count(*) AS requests,
                       count(*) FILTER (WHERE http_status = 200) AS served,
                       count(*) FILTER (WHERE status LIKE 'rejected_%') AS rejected,
                       coalesce(sum(cost_nanos), 0) / 1e9 AS cost_usd,
                       count(*) FILTER (WHERE cache = 'hit') AS cache_hits,
                       count(*) FILTER (WHERE cache = 'miss') AS cache_misses,
                       count(*) FILTER (WHERE fallback) AS fallbacks,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms)
                           FILTER (WHERE http_status = 200 AND NOT stream) AS latency_p50_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)
                           FILTER (WHERE http_status = 200 AND NOT stream) AS latency_p95_ms,
                       percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms - upstream_ms)
                           FILTER (WHERE http_status = 200 AND NOT stream AND upstream_ms IS NOT NULL) AS overhead_p50_ms,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms - upstream_ms)
                           FILTER (WHERE http_status = 200 AND NOT stream AND upstream_ms IS NOT NULL) AS overhead_p95_ms
                FROM request_logs WHERE created_at >= now() - interval '24 hours'
                """));
        long hits = ((Number) out.get("cache_hits")).longValue();
        long misses = ((Number) out.get("cache_misses")).longValue();
        out.put("cache_hit_rate", hits + misses == 0 ? null : (double) hits / (hits + misses));
        out.put("auto_routing", jdbc.queryForList("""
                SELECT route_tier AS tier, count(*) AS requests FROM request_logs
                WHERE route_tier IS NOT NULL AND created_at >= now() - interval '24 hours'
                GROUP BY route_tier ORDER BY route_tier
                """));
        out.put("by_provider", jdbc.queryForList("""
                SELECT resolved_provider AS provider, count(*) AS requests,
                       count(*) FILTER (WHERE fallback) AS as_fallback
                FROM request_logs WHERE resolved_provider IS NOT NULL AND created_at >= now() - interval '24 hours'
                GROUP BY 1 ORDER BY 1
                """));
        return out;
    }
}
