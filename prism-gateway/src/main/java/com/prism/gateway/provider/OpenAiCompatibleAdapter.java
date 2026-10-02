package com.prism.gateway.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.gateway.config.GatewayConfig.Provider;
import com.prism.gateway.provider.UpstreamException.Kind;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Adapter for providers that speak the OpenAI chat-completions wire format: the provided mocks,
 * OpenAI itself, OpenRouter, Groq, Ollama. Adds the provider's credential, sets the concrete model
 * name, applies timeouts, and classifies every failure for the failover logic.
 */
@Component
public class OpenAiCompatibleAdapter implements ProviderAdapter {

    private static final Duration STREAM_MAX_DURATION = Duration.ofMinutes(10);

    private final HttpClient http;
    private final ObjectMapper mapper;

    public OpenAiCompatibleAdapter(HttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public Completion complete(Provider provider, String model, ObjectNode request, CallOptions options) throws UpstreamException {
        ObjectNode body = request.deepCopy();
        body.put("model", provider.upstreamModel(model));
        body.remove("stream");
        body.remove("stream_options");

        CompletableFuture<HttpResponse<byte[]>> call =
                http.sendAsync(newRequest(provider, body, options.firstByteTimeout(), false), HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            response = call.get(options.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw UpstreamException.of(Kind.TOTAL_TIMEOUT,
                    provider.name() + " did not answer within " + options.totalTimeout().toMillis() + " ms");
        } catch (ExecutionException e) {
            throw classify(provider, e.getCause(), options);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            call.cancel(true);
            throw UpstreamException.of(Kind.NETWORK, "interrupted", e);
        }

        if (response.statusCode() / 100 != 2) {
            throw httpError(provider, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8), response.headers());
        }
        JsonNode json;
        try {
            json = mapper.readTree(response.body());
        } catch (IOException e) {
            throw UpstreamException.of(Kind.MALFORMED, provider.name() + " returned a body that is not JSON", e);
        }
        if (json == null || !json.path("choices").isArray() || json.path("choices").isEmpty()) {
            throw UpstreamException.of(Kind.MALFORMED, provider.name() + " returned a completion without choices");
        }
        return new Completion(response.body(), json, Usage.from(json.get("usage")));
    }

    @Override
    public UpstreamStream stream(Provider provider, String model, ObjectNode request, CallOptions options) throws UpstreamException {
        ObjectNode body = request.deepCopy();
        body.put("model", provider.upstreamModel(model));
        body.put("stream", true);
        if (!body.has("stream_options")) {
            // Real OpenAI-compatible APIs only send usage on streams when asked; the mocks always do.
            body.putObject("stream_options").put("include_usage", true);
        }

        LineQueue lines = new LineQueue();
        CompletableFuture<ResponseInfo> head = new CompletableFuture<>();
        BodyHandler<Void> handler = info -> {
            head.complete(info);
            return BodySubscribers.fromLineSubscriber(lines);
        };
        CompletableFuture<HttpResponse<Void>> exchange =
                http.sendAsync(newRequest(provider, body, options.firstByteTimeout(), true), handler);
        exchange.whenComplete((r, err) -> {
            if (err != null) {
                head.completeExceptionally(err);
                lines.fail(err);
            }
        });

        ResponseInfo info;
        try {
            info = head.get(options.firstByteTimeout().toMillis() + 250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            lines.cancel();
            throw UpstreamException.of(Kind.FIRST_BYTE_TIMEOUT,
                    provider.name() + " sent no response headers within " + options.firstByteTimeout().toMillis() + " ms");
        } catch (ExecutionException e) {
            lines.cancel();
            throw classify(provider, e.getCause(), options);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exchange.cancel(true);
            throw UpstreamException.of(Kind.NETWORK, "interrupted", e);
        }

        if (info.statusCode() / 100 != 2) {
            String errorBody = lines.drain(Duration.ofSeconds(1), 16_384);
            lines.cancel();
            throw httpError(provider, info.statusCode(), errorBody, info.headers());
        }

        UpstreamStream stream = new UpstreamStream(lines, options.streamIdleTimeout(), STREAM_MAX_DURATION,
                () -> exchange.cancel(true));
        try {
            stream.awaitFirstEvent(options.firstByteTimeout());
        } catch (UpstreamException e) {
            stream.close();
            throw e;
        }
        return stream;
    }

    private HttpRequest newRequest(Provider provider, ObjectNode body, Duration firstByteTimeout, boolean stream) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise request", e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .header("Authorization", "Bearer " + provider.apiKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (!stream) {
            // Careful: the JDK's request timeout runs until the HttpResponse is complete. With a
            // line-subscriber body that means the END of the stream, so on streams it would cut any
            // answer longer than the timeout. Streams use our own first-byte and idle timeouts instead.
            builder.timeout(firstByteTimeout);
        }
        return builder.build();
    }

    private UpstreamException classify(Provider provider, Throwable cause, CallOptions options) {
        if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
            return UpstreamException.of(Kind.CONNECT_FAILED, "cannot connect to " + provider.name(), cause);
        }
        if (cause instanceof HttpTimeoutException) {
            return UpstreamException.of(Kind.FIRST_BYTE_TIMEOUT,
                    provider.name() + " sent no response within " + options.firstByteTimeout().toMillis() + " ms", cause);
        }
        if (cause instanceof IOException && cause.getCause() instanceof ConnectException) {
            return UpstreamException.of(Kind.CONNECT_FAILED, "cannot connect to " + provider.name(), cause);
        }
        return UpstreamException.of(Kind.NETWORK,
                provider.name() + " connection failed: " + cause.getClass().getSimpleName(), cause);
    }

    /** Maps a provider error to our taxonomy. The provider's own message is kept for our logs, minus any secret. */
    private UpstreamException httpError(Provider provider, int status, String body, HttpHeaders headers) {
        String message = body;
        try {
            JsonNode json = mapper.readTree(body);
            if (json != null && json.path("error").has("message")) {
                message = json.path("error").path("message").asText();
            }
        } catch (IOException ignored) {
            // not JSON: keep the raw text
        }
        message = redact(message, provider.apiKey());
        if (message.length() > 300) {
            message = message.substring(0, 300) + "…";
        }
        Long retryAfter = headers.firstValue("retry-after").map(OpenAiCompatibleAdapter::parseRetryAfter).orElse(null);
        return UpstreamException.http(status, provider.name() + " returned " + status + ": " + message.strip(), retryAfter);
    }

    private static Long parseRetryAfter(String value) {
        try {
            return (long) (Double.parseDouble(value.trim()) * 1000);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String redact(String text, String secret) {
        if (text == null || secret == null || secret.length() < 4) {
            return text;
        }
        return text.replace(secret, "[redacted]");
    }
}
