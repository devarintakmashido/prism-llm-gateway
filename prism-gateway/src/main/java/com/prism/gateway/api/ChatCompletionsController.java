package com.prism.gateway.api;

import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.core.ChatPipeline;
import com.prism.gateway.core.PrismException;
import com.prism.gateway.tenant.Tenant;
import com.prism.gateway.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** The OpenAI-compatible data plane. */
@RestController
public class ChatCompletionsController {

    private final ChatPipeline pipeline;
    private final TenantService tenants;
    private final GatewayConfig config;

    public ChatCompletionsController(ChatPipeline pipeline, TenantService tenants, GatewayConfig config) {
        this.pipeline = pipeline;
        this.tenants = tenants;
        this.config = config;
    }

    /**
     * Runs on a virtual thread: the whole request, including a long stream, is ordinary blocking
     * code, and thousands of concurrent streams cost almost nothing while they wait on providers.
     */
    @PostMapping("/v1/chat/completions")
    public void chatCompletions(@RequestBody(required = false) byte[] body, HttpServletRequest request,
                                HttpServletResponse response) throws IOException {
        pipeline.handle(body == null ? new byte[0] : body, request, response);
    }

    /** OpenAI-style model list: the aliases this key may call. */
    @GetMapping("/v1/models")
    public ResponseEntity<Map<String, Object>> models(@RequestHeader(value = "Authorization", required = false) String auth) {
        Tenant tenant;
        try {
            tenant = tenants.authenticate(auth);
        } catch (PrismException e) {
            return ResponseEntity.status(e.error().httpStatus())
                    .body(Map.of("error", Map.of("message", e.getMessage(), "type", e.error().type())));
        }
        List<Map<String, Object>> data = tenant.allowlist().stream().sorted()
                .map(alias -> Map.<String, Object>of("id", alias, "object", "model", "owned_by", "prism",
                        "prism_kind", config.isAuto(alias) ? "auto-router" : "alias"))
                .toList();
        return ResponseEntity.ok(Map.of("object", "list", "data", data));
    }
}
