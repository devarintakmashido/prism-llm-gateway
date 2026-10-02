package com.prism.gateway.api;

import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.routing.ProviderHealth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Liveness plus a one-line view of each provider, for humans and load balancers. */
@RestController
public class HealthController {

    private final ProviderHealth health;
    private final PrismProperties props;

    public HealthController(ProviderHealth health, PrismProperties props) {
        this.health = health;
        this.props = props;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> providers = new LinkedHashMap<>();
        health.snapshot().forEach(s -> providers.put(s.provider(), s.state().name().toLowerCase()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("service", "prism-gateway");
        out.put("instance", props.instanceId());
        out.put("providers", providers);
        return out;
    }
}
