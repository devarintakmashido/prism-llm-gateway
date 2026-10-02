package com.prism.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.prism.gateway.TestFixtures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process OpenAI-compatible provider whose behaviour is chosen by the requested model name.
 * It covers what the provided mock cannot inject: streams that stop half-way, go silent, send
 * keep-alive comments, or return garbage.
 * <p>
 * It can also stand in for a whole provider (e.g. "alpha" in a full gateway test): models it does not
 * know, such as "alpha-small", then get the behaviour set with {@link #answerOtherModelsAs}.
 */
public final class FakeProvider implements AutoCloseable {

    static final String API_KEY = "sk-fake-provider-secret";
    private static final Set<String> BEHAVIOURS =
            Set.of("ok", "down", "slow", "garbage", "stream-ok", "stream-cut", "stream-idle", "stream-keepalive");

    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile String otherModels;

    public FakeProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** "ok" (a completion, or a stream when one is asked for) or "down" (503); null means 404. */
    public void answerOtherModelsAs(String behaviour) {
        otherModels = behaviour;
    }

    /** How many completion requests reached this provider. */
    public int requests() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        JsonNode body = TestFixtures.MAPPER.readTree(ex.getRequestBody());
        String model = body.path("model").asText();
        String behaviour = BEHAVIOURS.contains(model) ? model : String.valueOf(otherModels);
        if (behaviour.equals("ok") && body.path("stream").asBoolean(false)) {
            behaviour = "stream-ok";
        }
        try {
            switch (behaviour) {
                case "ok" -> json(ex, 200, """
                        {"id":"x","object":"chat.completion","model":"ok","choices":[{"index":0,
                         "message":{"role":"assistant","content":"hello there"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""");
                case "down" -> json(ex, 503, "{\"error\":{\"message\":\"down, key " + API_KEY + " rejected\"}}");
                case "slow" -> {
                    sleep(2000);
                    json(ex, 200, "{}");
                }
                case "garbage" -> json(ex, 200, "<html>oops</html>");
                case "stream-ok" -> stream(ex, false, false, false);
                case "stream-cut" -> stream(ex, true, false, false);
                case "stream-idle" -> stream(ex, false, true, false);
                case "stream-keepalive" -> stream(ex, false, false, true);
                default -> json(ex, 404, "{\"error\":{\"message\":\"no such model\"}}");
            }
        } finally {
            ex.close();
        }
    }

    private void stream(HttpExchange ex, boolean cutAfterTwo, boolean goSilent, boolean keepAlive) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        String[] words = {"one ", "two ", "three ", "four "};
        send(out, "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}");
        for (int i = 0; i < words.length; i++) {
            if (keepAlive) {
                out.write(": OPENROUTER PROCESSING\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            send(out, "{\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + words[i] + "\"},\"finish_reason\":null}]}");
            if (cutAfterTwo && i == 1) {
                return; // the provider dies mid-answer: no finish_reason, no usage, no [DONE]
            }
            if (goSilent && i == 0) {
                sleep(3000);
            }
        }
        send(out, "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4,\"total_tokens\":7}}");
        send(out, "[DONE]");
    }

    private static void send(OutputStream out, String data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
