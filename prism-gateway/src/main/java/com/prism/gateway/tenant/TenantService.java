package com.prism.gateway.tenant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prism.common.Keys;
import com.prism.gateway.core.PrismError;
import com.prism.gateway.core.PrismException;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Authenticates virtual keys. Keys are looked up by SHA-256 hash, and results (including "no such
 * key") are cached for a few seconds, so auth costs no database round trip on the hot path while a
 * key disabled in the control plane still stops working within seconds.
 */
@Service
@DependsOn("seedData")
public class TenantService {

    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private final JdbcTemplate jdbc;
    private final Cache<String, Optional<Tenant>> byKeyHash = Caffeine.newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(10_000)
            .build();

    public TenantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param authorization the raw Authorization header */
    public Tenant authenticate(String authorization) {
        String key = bearerToken(authorization);
        if (key == null) {
            throw new PrismException(PrismError.AUTHENTICATION,
                    "Missing API key. Send your virtual key as 'Authorization: Bearer <key>'.");
        }
        Optional<Tenant> tenant = byKeyHash.get(Keys.sha256(key), this::load);
        if (tenant.isEmpty()) {
            throw new PrismException(PrismError.AUTHENTICATION, "Invalid API key.");
        }
        if (!tenant.get().active()) {
            throw new PrismException(PrismError.AUTHENTICATION, "This API key has been disabled.");
        }
        return tenant.get();
    }

    public static String bearerToken(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String key = authorization.substring(7).trim();
        return key.isEmpty() ? null : key;
    }

    private Optional<Tenant> load(String keyHash) {
        List<Tenant> rows = jdbc.query("""
                SELECT id, team, key_hint, monthly_budget_nanos, rpm_limit, tpm_limit, model_allowlist,
                       cache_enabled, cache_threshold, status
                FROM tenants WHERE key_hash = ?
                """, (rs, i) -> new Tenant(
                rs.getLong("id"),
                rs.getString("team"),
                rs.getString("key_hint"),
                rs.getLong("monthly_budget_nanos"),
                rs.getInt("rpm_limit"),
                (Integer) rs.getObject("tpm_limit"),
                toSet(rs.getArray("model_allowlist")),
                rs.getBoolean("cache_enabled"),
                (Double) rs.getObject("cache_threshold"),
                rs.getString("status")), keyHash);
        return rows.stream().findFirst();
    }

    private static Set<String> toSet(Array array) throws SQLException {
        return Set.of((String[]) array.getArray());
    }
}
