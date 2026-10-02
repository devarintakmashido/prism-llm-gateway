package com.prism.controlplane.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cache effectiveness per key: hits, misses, hit rate, stored entries, and money saved (the original
 * cost of each cached answer times the number of times it was served again).
 */
@RestController
public class CacheController {

    private final JdbcTemplate jdbc;
    private final Tenants tenants;

    public CacheController(JdbcTemplate jdbc, Tenants tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @GetMapping("/admin/cache/stats")
    public Map<String, Object> stats(@RequestParam(required = false) String key,
                                     @RequestParam(required = false) String team,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        Window window = Window.parse(from, to);
        Map<String, Object> tenant = tenants.find(key, team);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.id, t.team, t.key_hint, t.cache_enabled, t.cache_threshold,
                       count(l.request_id) FILTER (WHERE l.cache = 'hit')    AS hits,
                       count(l.request_id) FILTER (WHERE l.cache = 'miss')   AS misses,
                       count(l.request_id) FILTER (WHERE l.cache = 'bypass') AS bypassed,
                       coalesce(e.entries, 0) AS entries,
                       coalesce(e.saved_nanos, 0) / 1e9 AS saved_usd
                FROM tenants t
                LEFT JOIN request_logs l ON l.tenant_id = t.id AND l.created_at >= ? AND l.created_at < ?
                LEFT JOIN (SELECT tenant_id, count(*) AS entries, sum(cost_nanos * hit_count) AS saved_nanos
                           FROM cache_entries WHERE expires_at > now() GROUP BY tenant_id) e ON e.tenant_id = t.id
                GROUP BY t.id, e.entries, e.saved_nanos
                ORDER BY t.team
                """, window.fromTs(), window.toTs());

        long hits = 0, misses = 0;
        List<Map<String, Object>> perKey = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (tenant != null && !r.get("id").equals(tenant.get("id"))) {
                continue;
            }
            long h = ((Number) r.get("hits")).longValue();
            long m = ((Number) r.get("misses")).longValue();
            hits += h;
            misses += m;
            Map<String, Object> entry = new LinkedHashMap<>(r);
            entry.remove("id");
            entry.put("hit_rate", h + m == 0 ? null : (double) h / (h + m));
            perKey.add(entry);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", window.from().toString());
        out.put("to", window.to().toString());
        out.put("hits", hits);
        out.put("misses", misses);
        out.put("hit_rate", hits + misses == 0 ? null : (double) hits / (hits + misses));
        out.put("keys", perKey);
        return out;
    }

    /** Clears one key's cache entries (e.g. before a demo). Never touches other keys. */
    @DeleteMapping("/admin/cache")
    public Map<String, Object> clear(@RequestParam(required = false) String key,
                                     @RequestParam(required = false) String team) {
        Map<String, Object> tenant = tenants.find(key, team);
        if (tenant == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "key or team is required");
        }
        int deleted = jdbc.update("DELETE FROM cache_entries WHERE tenant_id = ?", tenant.get("id"));
        return Map.of("team", tenant.get("team"), "deleted_entries", deleted);
    }
}
