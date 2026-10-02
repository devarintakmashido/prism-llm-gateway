package com.prism.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.router.AutoRouter;
import com.prism.gateway.router.RouteDecision;
import com.prism.gateway.routing.HealthProber;
import com.prism.gateway.routing.ProviderHealth;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin endpoints that belong to the data plane because the data lives here: the router (for the
 * routing eval) and this instance's view of provider health. Protected by the admin token.
 */
@RestController
public class GatewayAdminController {

    private final AutoRouter router;
    private final ProviderHealth health;
    private final HealthProber prober;
    private final GatewayConfig config;
    private final PrismProperties props;

    public GatewayAdminController(AutoRouter router, ProviderHealth health, HealthProber prober,
                                  GatewayConfig config, PrismProperties props) {
        this.router = router;
        this.health = health;
        this.prober = prober;
        this.config = config;
        this.props = props;
    }

    /** Classification only (no upstream call): {"prompt": "..."} -> tier, score and the reasons. */
    @PostMapping("/admin/route")
    public ResponseEntity<?> route(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody JsonNode body) {
        if (!isAdmin(auth)) {
            return unauthorized();
        }
        String prompt = body.path("prompt").asText("");
        if (prompt.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", Map.of("message", "'prompt' is required",
                    "type", "invalid_request_error")));
        }
        RouteDecision d = body.path("use_cache").asBoolean(false) ? router.classify(prompt) : router.classifyUncached(prompt);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tier", config.tierFor(d.difficulty()));
        out.put("difficulty", d.difficulty());
        out.put("score", d.score());
        out.put("signal_score", d.signalScore());
        out.put("knn_score", d.knnScore());
        out.put("uncertain", d.uncertain());
        out.put("signals", d.signals());
        out.put("neighbours", d.neighbours());
        out.put("instruction", d.instruction());
        out.put("reason", d.reason());
        out.put("micros", d.micros());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/admin/providers/health")
    public ResponseEntity<?> providerHealth(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!isAdmin(auth)) {
            return unauthorized();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instance", props.instanceId());
        out.put("probe_models", prober.probeModels());
        out.put("providers", health.snapshot());
        return ResponseEntity.ok(out);
    }

    private boolean isAdmin(String auth) {
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        byte[] given = auth.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        byte[] expected = props.adminToken().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, expected); // constant-time comparison
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", Map.of("message", "Admin token required: 'Authorization: Bearer <admin token>'",
                        "type", "authentication_error")));
    }
}
