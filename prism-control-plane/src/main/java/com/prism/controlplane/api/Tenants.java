package com.prism.controlplane.api;

import com.prism.common.Keys;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/** Finds a tenant by its virtual key (hashed before lookup, never stored) or by team name. */
@Component
class Tenants {

    private final JdbcTemplate jdbc;

    Tenants(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the tenant row, null if neither key nor team was given, 404 if not found */
    Map<String, Object> find(String key, String team) {
        List<Map<String, Object>> rows;
        if (key != null && !key.isBlank()) {
            rows = jdbc.queryForList("SELECT * FROM tenants WHERE key_hash = ?", Keys.sha256(key.trim()));
        } else if (team != null && !team.isBlank()) {
            rows = jdbc.queryForList("SELECT * FROM tenants WHERE team = ?", team.trim());
        } else {
            return null;
        }
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such key or team");
        }
        return rows.getFirst();
    }
}
