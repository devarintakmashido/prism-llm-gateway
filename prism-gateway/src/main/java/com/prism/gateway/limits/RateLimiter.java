package com.prism.gateway.limits;

import com.prism.gateway.tenant.Tenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Requests-per-minute limit per virtual key: an exact sliding window kept in Redis and updated by a
 * single Lua script (check and record in one atomic step, never read-then-write). Because the state
 * lives in Redis, the limit holds across any number of gateway instances.
 * <p>
 * If Redis is unreachable we fail open (admit and log a warning): a limiter outage should not take
 * the whole gateway down. This is the same default as Envoy's global rate limit filter.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final long WINDOW_MS = 60_000;

    public record Decision(boolean allowed, int limit, long remaining, long retryAfterMs, boolean failOpen) {
    }

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;

    public RateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/sliding_window.lua"));
        this.script.setResultType(List.class);
    }

    public Decision acquire(Tenant tenant, String requestId) {
        try {
            List<?> result = redis.execute(script, List.of("prism:rl:rpm:" + tenant.id()),
                    Long.toString(WINDOW_MS), Integer.toString(tenant.rpmLimit()), requestId);
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            long remaining = ((Number) result.get(1)).longValue();
            long retryAfter = ((Number) result.get(2)).longValue();
            return new Decision(allowed, tenant.rpmLimit(), remaining, retryAfter, false);
        } catch (DataAccessException e) {
            log.warn("Rate limiter unavailable, failing open for tenant {}: {}", tenant.team(), e.getMessage());
            return new Decision(true, tenant.rpmLimit(), -1, 0, true);
        }
    }
}
