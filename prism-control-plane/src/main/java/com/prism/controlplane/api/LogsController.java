package com.prism.controlplane.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recent request log entries, newest first. Each entry is enough to debug a request on its own:
 * who sent it, how it was routed and why, which providers were tried and what each returned, cache
 * decision and similarity, tokens, cost, and latency. Prompt and response bodies are never logged.
 */
@RestController
public class LogsController {

    private final JdbcTemplate jdbc;
    private final Tenants tenants;
    private final ObjectMapper mapper;

    public LogsController(JdbcTemplate jdbc, Tenants tenants, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.mapper = mapper;
    }

    @GetMapping("/admin/logs")
    public List<Map<String, Object>> logs(@RequestParam(required = false) String key,
                                          @RequestParam(required = false) String team,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> tenant = tenants.find(key, team);
        StringBuilder sql = new StringBuilder("""
                SELECT request_id, created_at, team, key_hint, requested_model, resolved_alias, resolved_provider,
                       resolved_model, status, http_status, error_type, error_message, stream, prompt_tokens,
                       completion_tokens, cost_nanos / 1e9 AS cost_usd, cost_estimated, cache, cache_similarity,
                       cache_note, fallback, retries, attempts::text AS attempts, route_tier, route_reason,
                       latency_ms, upstream_ms, ttft_ms, gateway_instance
                FROM request_logs WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();
        if (tenant != null) {
            sql.append(" AND tenant_id = ?");
            args.add(tenant.get("id"));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>(row);
            Object attempts = entry.get("attempts");
            if (attempts instanceof String s) {
                try {
                    entry.put("attempts", mapper.readTree(s));
                } catch (Exception ignored) {
                    // leave as text
                }
            }
            out.add(entry);
        }
        return out;
    }
}
