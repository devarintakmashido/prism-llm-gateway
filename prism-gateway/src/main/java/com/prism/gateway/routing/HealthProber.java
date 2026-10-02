package com.prism.gateway.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.GatewayConfig.ModelRoute;
import com.prism.gateway.config.GatewayConfig.Provider;
import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.provider.ProviderAdapter;
import com.prism.gateway.provider.ProviderAdapter.CallOptions;
import com.prism.gateway.provider.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Active health checks. Every few seconds, each provider gets a real one-token completion, which
 * exercises the same path as live traffic. A plain GET /health would not do: the mock provider
 * answers /health with "ok" while its completions return 503.
 * <p>
 * Probing is what brings a provider back quickly after an outage: one successful probe marks it
 * healthy again, so traffic returns to the primary within one probe interval (1 s), without waiting
 * for a live request to take the risk. Probes can be switched off per provider (e.g. paid real APIs),
 * which then rely on passive health from live traffic alone.
 * <p>
 * A probe can outlast the interval (a hung provider takes the whole first-byte timeout), so each
 * provider has at most one probe in flight: probes never pile up connections on a provider that is
 * already struggling.
 * <p>
 * Each gateway instance probes on its own, the way Envoy's active health checking works, and
 * publishes its view to Redis so the control plane can show the whole fleet.
 */
@Component
public class HealthProber {

    private static final Logger log = LoggerFactory.getLogger(HealthProber.class);

    private final GatewayConfig config;
    private final ProviderAdapter adapter;
    private final ProviderHealth health;
    private final PrismProperties props;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final Map<String, String> probeModels = new LinkedHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor();

    public HealthProber(GatewayConfig config, ProviderAdapter adapter, ProviderHealth health,
                        PrismProperties props, ObjectMapper mapper, StringRedisTemplate redis) {
        this.config = config;
        this.adapter = adapter;
        this.health = health;
        this.props = props;
        this.mapper = mapper;
        this.redis = redis;
        // Probe each provider with the cheapest model it serves in any alias chain.
        config.aliasChains().values().stream().flatMap(List::stream)
                .sorted((a, b) -> Boolean.compare(b.model().endsWith("-small"), a.model().endsWith("-small")))
                .forEach(r -> probeModels.putIfAbsent(r.provider(), r.model()));
    }

    @Scheduled(fixedDelayString = "${prism.health.probe-interval}", initialDelayString = "${prism.health.probe-interval}")
    public void probeAll() {
        if (!props.health().probeEnabled()) {
            return;
        }
        probeModels.forEach((providerName, model) -> {
            Provider provider = config.provider(providerName);
            if (provider.probe() && inFlight.add(providerName)) {
                probes.submit(() -> {
                    try {
                        probe(provider, new ModelRoute(providerName, model));
                    } finally {
                        inFlight.remove(providerName);
                    }
                });
            }
        });
    }

    private void probe(Provider provider, ModelRoute route) {
        ObjectNode request = mapper.createObjectNode();
        request.putArray("messages").addObject().put("role", "user").put("content", "ping");
        request.put("max_tokens", 1);
        CallOptions options = new CallOptions(provider.firstByteTimeout(),
                provider.firstByteTimeout().plusSeconds(1), props.upstream().streamIdleTimeout());
        long start = System.nanoTime();
        try {
            adapter.complete(provider, route.model(), request, options);
            health.recordSuccess(provider.name(), (System.nanoTime() - start) / 1_000_000, true);
        } catch (UpstreamException e) {
            health.recordFailure(provider.name(), (System.nanoTime() - start) / 1_000_000,
                    "probe " + e.label() + ": " + e.getMessage(), true);
        } catch (RuntimeException e) {
            log.warn("health probe for {} failed unexpectedly", provider.name(), e);
        }
    }

    /** Publishes this instance's view of provider health for the control plane (expires if we die). */
    @Scheduled(fixedDelayString = "${prism.health.publish-interval}")
    public void publish() {
        try {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("instance", props.instanceId());
            view.put("at", Instant.now().toString());
            view.put("providers", health.snapshot());
            redis.opsForValue().set("prism:health:" + props.instanceId(), mapper.writeValueAsString(view),
                    props.health().publishInterval().multipliedBy(5));
        } catch (Exception e) {
            log.debug("could not publish health snapshot: {}", e.toString());
        }
    }

    public Map<String, String> probeModels() {
        return Map.copyOf(probeModels);
    }
}
