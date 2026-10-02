package com.prism.controlplane.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Fleet view of provider health. Every gateway instance publishes its own view (from live traffic
 * and its probes) to Redis with a short expiry; this endpoint merges them. A provider's overall
 * state is the worst state any live instance reports.
 */
@RestController
public class ProvidersController {

    private static final List<String> SEVERITY = List.of("HEALTHY", "DEGRADED", "UNHEALTHY");

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public ProvidersController(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @GetMapping("/admin/providers/health")
    public Map<String, Object> health() throws Exception {
        Set<String> keys = redis.keys("prism:health:*");
        List<JsonNode> instances = new ArrayList<>();
        if (keys != null) {
            for (String k : keys) {
                String json = redis.opsForValue().get(k);
                if (json != null) {
                    instances.add(mapper.readTree(json));
                }
            }
        }
        Map<String, Map<String, Object>> providers = new TreeMap<>();
        for (JsonNode instance : instances) {
            for (JsonNode p : instance.path("providers")) {
                String name = p.path("provider").asText();
                Map<String, Object> agg = providers.computeIfAbsent(name, n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("provider", n);
                    m.put("state", "HEALTHY");
                    m.put("instances", new ArrayList<Map<String, Object>>());
                    return m;
                });
                String state = p.path("state").asText("HEALTHY");
                if (SEVERITY.indexOf(state) > SEVERITY.indexOf((String) agg.get("state"))) {
                    agg.put("state", state);
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) agg.get("instances");
                Map<String, Object> view = mapper.convertValue(p, Map.class);
                view.put("instance", instance.path("instance").asText());
                list.add(view);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateway_instances", instances.stream().map(i -> i.path("instance").asText()).sorted().toList());
        out.put("providers", providers.values());
        return out;
    }
}
