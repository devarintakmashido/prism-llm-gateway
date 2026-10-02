package com.prism.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.common.Money;
import com.prism.gateway.cache.CachePolicy;
import com.prism.gateway.cache.PromptKey;
import com.prism.gateway.cache.SemanticCache;
import com.prism.gateway.config.GatewayConfig;
import com.prism.gateway.config.GatewayConfig.ModelRoute;
import com.prism.gateway.config.GatewayConfig.Provider;
import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.embedding.EmbeddingService;
import com.prism.gateway.limits.BudgetService;
import com.prism.gateway.limits.RateLimiter;
import com.prism.gateway.metering.PriceTable;
import com.prism.gateway.provider.Completion;
import com.prism.gateway.provider.ProviderAdapter;
import com.prism.gateway.provider.ProviderAdapter.CallOptions;
import com.prism.gateway.provider.UpstreamException;
import com.prism.gateway.provider.UpstreamStream;
import com.prism.gateway.provider.Usage;
import com.prism.gateway.router.AutoRouter;
import com.prism.gateway.router.RouteDecision;
import com.prism.gateway.routing.FailoverExecutor;
import com.prism.gateway.routing.FailoverExecutor.AllFailed;
import com.prism.gateway.routing.FailoverExecutor.Served;
import com.prism.gateway.routing.ProviderHealth;
import com.prism.gateway.tenant.Tenant;
import com.prism.gateway.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One chat-completion request, end to end. The order of checks is deliberate and documented:
 *
 * <ol>
 *   <li>authenticate the virtual key (401)</li>
 *   <li>validate the body (400)</li>
 *   <li>the model or alias must exist (404)</li>
 *   <li>and be on the key's allowlist (403)</li>
 *   <li>rate limit, requests per minute (429)</li>
 *   <li>auto alias: classify difficulty, pick a tier</li>
 *   <li>budget: reserve the estimated cost (402)</li>
 *   <li>semantic cache lookup</li>
 *   <li>upstream call with retries and failover (502 / 504)</li>
 *   <li>meter the cost from provider usage, store in cache, log</li>
 * </ol>
 * Cheap checks come first, and anything that counts against a limit comes after the checks that
 * can reject a request outright. Every request, including every rejection and every client that
 * disconnects half-way, produces exactly one log row: the happy paths write it before releasing the
 * response, and a finally block writes it for everything else (the writer ignores a second call).
 */
@Service
public class ChatPipeline {

    private static final Logger log = LoggerFactory.getLogger(ChatPipeline.class);
    private static final int DEFAULT_OUTPUT_TOKEN_ESTIMATE = 512;

    private final ObjectMapper mapper;
    private final PrismProperties props;
    private final GatewayConfig config;
    private final TenantService tenants;
    private final RateLimiter rateLimiter;
    private final BudgetService budget;
    private final PriceTable prices;
    private final AutoRouter router;
    private final EmbeddingService embeddings;
    private final SemanticCache cache;
    private final FailoverExecutor failover;
    private final ProviderAdapter adapter;
    private final ProviderHealth health;
    private final RequestLogWriter logs;

    public ChatPipeline(ObjectMapper mapper, PrismProperties props, GatewayConfig config, TenantService tenants,
                        RateLimiter rateLimiter, BudgetService budget, PriceTable prices, AutoRouter router,
                        EmbeddingService embeddings, SemanticCache cache, FailoverExecutor failover,
                        ProviderAdapter adapter, ProviderHealth health, RequestLogWriter logs) {
        this.mapper = mapper;
        this.props = props;
        this.config = config;
        this.tenants = tenants;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
        this.prices = prices;
        this.router = router;
        this.embeddings = embeddings;
        this.cache = cache;
        this.failover = failover;
        this.adapter = adapter;
        this.health = health;
        this.logs = logs;
    }

    public void handle(byte[] raw, HttpServletRequest request, HttpServletResponse response) throws IOException {
        RequestTrace trace = new RequestTrace("req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                props.instanceId());
        response.setHeader("x-request-id", trace.requestId());
        BudgetService.Reservation reservation = null;
        try {
            trace.requestedModel(ChatRequest.peekModel(raw, mapper));
            Tenant tenant = trace.time("auth", () -> tenants.authenticate(request.getHeader("Authorization")));
            trace.tenant(tenant);

            ChatRequest chat = ChatRequest.parse(raw, mapper);
            trace.stream(chat.stream());
            String requested = chat.model();
            if (!isKnownModel(requested)) {
                throw new PrismException(PrismError.MODEL_NOT_FOUND, "The model '" + requested
                        + "' does not exist. Use one of the aliases: " + String.join(", ", knownAliases()) + ".");
            }
            if (!tenant.allows(requested)) {
                throw new PrismException(PrismError.MODEL_NOT_ALLOWED, "This key is not allowed to use '" + requested
                        + "'. Allowed: " + String.join(", ", new TreeSet<>(tenant.allowlist())) + ".");
            }

            RateLimiter.Decision limit = trace.time("ratelimit", () -> rateLimiter.acquire(tenant, trace.requestId()));
            response.setHeader("x-ratelimit-limit-requests", Integer.toString(limit.limit()));
            if (limit.remaining() >= 0) {
                response.setHeader("x-ratelimit-remaining-requests", Long.toString(limit.remaining()));
            }
            if (!limit.allowed()) {
                long retryAfter = Math.max(1, (limit.retryAfterMs() + 999) / 1000);
                throw new PrismException(PrismError.RATE_LIMITED, "Rate limit of " + limit.limit()
                        + " requests per minute exceeded for this key. Retry in " + retryAfter + "s.", retryAfter);
            }

            String alias = requested;
            if (config.isAuto(requested)) {
                RouteDecision decision = trace.time("route", () -> router.classify(chat.lastUserText()));
                alias = tierFor(decision, tenant, trace);
                response.setHeader("x-prism-route", String.format(Locale.ROOT, "%s; difficulty=%s; score=%.2f%s",
                        alias, decision.difficulty(), decision.score(), decision.cached() ? "; cached" : ""));
            }
            trace.resolvedAlias(alias);
            List<ModelRoute> chain = chainFor(alias);

            long estimate = estimateCostNanos(chat, chain);
            reservation = trace.time("budget", () -> budget.reserve(tenant, estimate));

            PromptKey key = null;
            String bypass = CachePolicy.bypassReason(tenant, chat, request.getHeader("Cache-Control"));
            if (bypass != null) {
                trace.cache(tenant.cacheEnabled() ? "bypass" : "disabled", null, bypass);
            } else {
                PromptKey k = PromptKey.of(alias, chat, mapper, props.cache().maxSemanticWords());
                key = k;
                // Embedded only if the exact-match stage misses.
                SemanticCache.Lookup lookup = trace.time("cache",
                        () -> cache.lookup(tenant, k, () -> embeddings.embed(k.text())));
                trace.cache(lookup.hit() != null ? "hit" : "miss", lookup.bestSimilarity(), lookup.note());
                if (lookup.hit() != null) {
                    serveFromCache(chat, lookup.hit(), trace, response);
                    return;
                }
            }

            if (chat.stream()) {
                streamFromUpstream(chat, tenant, chain, key, trace, reservation, response);
            } else {
                completeFromUpstream(chat, tenant, chain, key, trace, reservation, response);
            }
        } catch (PrismException e) {
            trace.fail(e.error(), e.getMessage());
            writeError(response, e, trace);
        } catch (RuntimeException e) {
            log.error("Unexpected error in request {}", trace.requestId(), e);
            PrismException internal = new PrismException(PrismError.INTERNAL, "Internal gateway error (request "
                    + trace.requestId() + ").");
            trace.fail(internal.error(), e.toString());
            writeError(response, internal, trace);
        } catch (IOException clientGone) {
            // The client left while we were writing to it (a cached answer being replayed as a stream, or
            // the first flush of a stream). Nothing more can be sent; the request is still logged below.
            if (!trace.logged()) {
                trace.outcome("client_cancelled", trace.httpStatus(), null, "client disconnected: " + clientGone);
            }
        } finally {
            logs.write(trace, reservation); // no-op if the happy path already wrote it
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Non-streaming

    private void completeFromUpstream(ChatRequest chat, Tenant tenant, List<ModelRoute> chain, PromptKey key,
                                      RequestTrace trace, BudgetService.Reservation reservation,
                                      HttpServletResponse response) throws IOException {
        long start = System.nanoTime();
        Served<Completion> served;
        try {
            served = failover.execute(chain, props.upstream().totalTimeout(),
                    (provider, route, remaining) -> adapter.complete(provider, route.model(), chat.body(),
                            callOptions(provider, remaining)));
        } catch (AllFailed e) {
            trace.recordStage("upstream", start);
            trace.attempts(e.attempts());
            throw upstreamFailure(e, trace.resolvedAlias());
        }
        trace.recordStage("upstream", start);
        trace.upstreamMs((System.nanoTime() - start) / 1_000_000);

        ModelRoute route = served.route();
        Completion completion = served.value();
        trace.served(route, served.fallback(), served.attempts(), served.retries());

        Usage usage = completion.usage();
        boolean estimated = usage == null;
        if (estimated) {
            usage = estimateUsage(chat, completion.content());
        }
        long cost = prices.costNanos(route.model(), usage.promptTokens(), usage.completionTokens());
        trace.usage(usage.promptTokens(), usage.completionTokens(), cost, estimated);

        if (key != null && CachePolicy.cacheableResponse(completion.finishReason(), completion.content())) {
            store(tenant, key, completion.json(), route, cost);
        }

        prismHeaders(response, route.label(), "miss", served.fallback(), Money.format(cost));
        response.setHeader("Server-Timing", trace.serverTiming());
        logs.write(trace, reservation);

        response.setStatus(200);
        response.setContentType("application/json");
        response.getOutputStream().write(completion.body());
    }

    // ------------------------------------------------------------------------------------------------
    // Streaming

    private void streamFromUpstream(ChatRequest chat, Tenant tenant, List<ModelRoute> chain, PromptKey key,
                                    RequestTrace trace, BudgetService.Reservation reservation,
                                    HttpServletResponse response) throws IOException {
        long start = System.nanoTime();
        Served<UpstreamStream> served;
        try {
            served = failover.execute(chain, props.upstream().totalTimeout(),
                    (provider, route, remaining) -> adapter.stream(provider, route.model(), chat.body(),
                            callOptions(provider, remaining)));
        } catch (AllFailed e) {
            trace.recordStage("upstream", start);
            trace.attempts(e.attempts());
            throw upstreamFailure(e, trace.resolvedAlias());
        }
        trace.recordStage("upstream-first-event", start);
        ModelRoute route = served.route();
        trace.served(route, served.fallback(), served.attempts(), served.retries());

        OutputStream out;
        SseRelay.Outcome outcome;
        try (UpstreamStream stream = served.value()) { // closed even if the client is gone before the first flush
            // Headers go out now, before the body: provider, cache and fallback are known, cost is not yet.
            response.setStatus(200);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no"); // tell nginx not to buffer this response
            prismHeaders(response, route.label(), "miss", served.fallback(), null);
            response.setHeader("Server-Timing", trace.serverTiming());
            out = response.getOutputStream();
            response.flushBuffer();
            outcome = SseRelay.relay(stream, out, mapper, trace.requestId(), trace.startNanos());
        }
        trace.upstreamMs((System.nanoTime() - start) / 1_000_000);
        trace.ttftMs(outcome.ttftMs());

        Usage usage = outcome.usage();
        boolean estimated = usage == null;
        if (estimated) {
            usage = estimateUsage(chat, outcome.content());
        }
        long cost = prices.costNanos(route.model(), usage.promptTokens(), usage.completionTokens());
        trace.usage(usage.promptTokens(), usage.completionTokens(), cost, estimated);

        switch (outcome.status()) {
            case COMPLETED -> {
                if (key != null && CachePolicy.cacheableResponse(outcome.finishReason(), outcome.content())) {
                    store(tenant, key, completionJson(route, outcome), route, cost);
                }
            }
            case STREAM_ERROR -> {
                health.recordFailure(route.provider(), trace.upstreamMs(), "mid-stream: " + outcome.error().getMessage(), false);
                trace.outcome("stream_error", 200, PrismError.UPSTREAM_ERROR.type(), outcome.error().getMessage());
            }
            case CLIENT_GONE -> trace.outcome("client_cancelled", 200, null, "client disconnected mid-stream");
        }

        // Record usage before the client sees the end of the stream.
        logs.write(trace, reservation);
        if (outcome.status() != SseRelay.Status.CLIENT_GONE) {
            try {
                SseRelay.writeComment(out, "prism-cost-usd=" + Money.format(cost));
                SseRelay.writeDone(out);
            } catch (IOException ignored) {
                // client left right at the end; nothing to do
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Cache hits

    private void serveFromCache(ChatRequest chat, SemanticCache.Hit hit, RequestTrace trace, HttpServletResponse response)
            throws IOException {
        trace.usage(0, 0, 0, false);
        trace.outcome("cache_hit", 200, null, null);
        response.setHeader("x-prism-cache-similarity", String.format(Locale.ROOT, "%.4f", hit.similarity()));
        response.setHeader("x-prism-cache-source", hit.servedBy());
        prismHeaders(response, "cache", "hit", false, "0");
        response.setHeader("Server-Timing", trace.serverTiming());

        ObjectNode body = hit.response().deepCopy();
        body.put("id", "chatcmpl-" + trace.requestId());
        body.put("created", System.currentTimeMillis() / 1000);
        ObjectNode usage = body.putObject("usage"); // nothing was billed for this answer
        usage.put("prompt_tokens", 0).put("completion_tokens", 0).put("total_tokens", 0);

        response.setStatus(200);
        if (chat.stream()) {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache");
            response.setHeader("X-Accel-Buffering", "no");
            OutputStream out = response.getOutputStream();
            SseRelay.replay(out, mapper, trace.requestId(), body);
            SseRelay.writeComment(out, "prism-cost-usd=0");
            SseRelay.writeDone(out);
        } else {
            response.setContentType("application/json");
            response.getOutputStream().write(mapper.writeValueAsBytes(body));
        }
    }

    private void store(Tenant tenant, PromptKey key, JsonNode completion, ModelRoute route, long cost) {
        try {
            // EmbeddingService memoises recent texts, so a prompt already embedded for the lookup is not embedded again.
            float[] embedding = key.semanticEligible() ? embeddings.embed(key.text()) : null;
            cache.store(tenant, key, embedding, completion, route.label(), cost);
        } catch (RuntimeException e) {
            log.warn("Could not store cache entry: {}", e.getMessage());
        }
    }

    private ObjectNode completionJson(ModelRoute route, SseRelay.Outcome outcome) {
        ObjectNode c = mapper.createObjectNode();
        c.put("object", "chat.completion");
        c.put("model", route.model());
        ObjectNode choice = c.putArray("choices").addObject();
        choice.put("index", 0);
        choice.putObject("message").put("role", "assistant").put("content", outcome.content());
        choice.put("finish_reason", outcome.finishReason());
        return c;
    }

    // ------------------------------------------------------------------------------------------------
    // Resolution helpers

    private boolean isKnownModel(String model) {
        return config.isAuto(model) || config.isTierAlias(model)
                || (prices.isPriced(model) && config.providerFor(model).isPresent());
    }

    private List<String> knownAliases() {
        TreeSet<String> aliases = new TreeSet<>(config.aliasChains().keySet());
        aliases.addAll(config.autoAliases());
        return List.copyOf(aliases);
    }

    private List<ModelRoute> chainFor(String aliasOrModel) {
        List<ModelRoute> chain = config.aliasChains().get(aliasOrModel);
        if (chain != null) {
            return chain;
        }
        String provider = config.providerFor(aliasOrModel).orElseThrow();
        return List.of(new ModelRoute(provider, aliasOrModel)); // a concrete model: no fallbacks
    }

    /** Maps the router's difficulty to a tier alias, respecting the key's allowlist. */
    private String tierFor(RouteDecision decision, Tenant tenant, RequestTrace trace) {
        String tier = config.tierFor(decision.difficulty());
        String reason = decision.reason();
        if (!tenant.allows(tier)) {
            String allowed = config.difficultyTiers().values().stream().filter(tenant::allows).findFirst()
                    .orElseThrow(() -> new PrismException(PrismError.MODEL_NOT_ALLOWED,
                            "auto needs at least one of " + config.difficultyTiers().values() + " on the key's allowlist."));
            reason += "; tier '" + tier + "' not on allowlist, used '" + allowed + "'";
            tier = allowed;
        }
        trace.route(tier, reason);
        return tier;
    }

    private CallOptions callOptions(Provider provider, Duration remaining) {
        Duration firstByte = min(provider.firstByteTimeout(), remaining);
        Duration total = min(props.upstream().totalTimeout(), remaining);
        return new CallOptions(firstByte, total, props.upstream().streamIdleTimeout());
    }

    /** Worst-case cost of this request across its chain, used for the budget reservation. */
    private long estimateCostNanos(ChatRequest chat, List<ModelRoute> chain) {
        long promptTokens = chat.estimatedPromptTokens();
        Integer max = chat.maxOutputTokens();
        long outputTokens = max != null && max > 0 ? max : DEFAULT_OUTPUT_TOKEN_ESTIMATE;
        return chain.stream()
                .mapToLong(r -> prices.costNanos(r.model(), promptTokens, outputTokens))
                .max().orElse(0);
    }

    /** Only used when a provider omits usage; the log marks such costs as estimated. */
    private static Usage estimateUsage(ChatRequest chat, String completion) {
        String text = completion == null ? "" : completion.strip();
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        return new Usage((int) chat.estimatedPromptTokens(), Math.max(words, text.length() / 4));
    }

    private static PrismException upstreamFailure(AllFailed e, String alias) {
        UpstreamException last = e.last();
        String attempts = e.attempts().stream()
                .map(a -> a.provider() + "/" + a.model() + " " + a.outcome())
                .collect(Collectors.joining(", "));
        if (last != null && last.isCallerError()) {
            return new PrismException(PrismError.INVALID_REQUEST, "The provider rejected the request: " + last.getMessage());
        }
        boolean allTimeouts = !e.attempts().isEmpty() && e.attempts().stream().allMatch(a -> a.outcome().contains("timeout"));
        if (allTimeouts) {
            return new PrismException(PrismError.UPSTREAM_TIMEOUT,
                    "No provider for '" + alias + "' answered in time (" + attempts + ").");
        }
        return new PrismException(PrismError.UPSTREAM_ERROR,
                "All providers for '" + alias + "' failed (" + (attempts.isEmpty() ? "none available" : attempts) + ").");
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    // ------------------------------------------------------------------------------------------------
    // Responses

    /** The header contract, on every response. x-prism-cost-usd is omitted only on live streams. */
    private static void prismHeaders(HttpServletResponse response, String provider, String cache, boolean fallback,
                                     String costUsd) {
        response.setHeader("x-prism-provider", provider);
        response.setHeader("x-prism-cache", cache);
        response.setHeader("x-prism-fallback", Boolean.toString(fallback));
        if (costUsd != null) {
            response.setHeader("x-prism-cost-usd", costUsd);
        }
    }

    private void writeError(HttpServletResponse response, PrismException e, RequestTrace trace) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        prismHeaders(response, "none", "miss", false, "0");
        response.setHeader("Server-Timing", trace.serverTiming());
        if (e.retryAfterSeconds() != null) {
            response.setHeader("Retry-After", Long.toString(e.retryAfterSeconds()));
        }
        ObjectNode body = mapper.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("message", e.getMessage());
        error.put("type", e.error().type());
        error.put("code", e.error().type());
        error.putNull("param");
        error.put("request_id", trace.requestId());
        response.setStatus(e.error().httpStatus());
        response.setContentType("application/json");
        response.getOutputStream().write(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
