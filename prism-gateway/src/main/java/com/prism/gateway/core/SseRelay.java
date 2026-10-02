package com.prism.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.gateway.provider.UpstreamException;
import com.prism.gateway.provider.UpstreamStream;
import com.prism.gateway.provider.Usage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streams a provider's SSE events to the client as they arrive. On a virtual thread this is a plain
 * loop: read an event, write it, flush. Nothing is buffered, and the relay only inspects each chunk
 * on the way through to pick up the text (for the cache) and the final usage (for metering).
 * <p>
 * Mid-stream failure: if the provider dies after tokens have been sent, we do <b>not</b> retry on
 * another provider. Splicing two models' output into one answer would be silently wrong. Instead
 * the client receives an error event with finish_reason "error" (the same shape OpenRouter uses),
 * then [DONE]. The request is logged as stream_error.
 * <p>
 * The relay never writes [DONE] itself: the pipeline does, after recording usage and cost.
 */
public final class SseRelay {

    public enum Status { COMPLETED, STREAM_ERROR, CLIENT_GONE }

    public record Outcome(Status status, String content, String finishReason, Usage usage, Long ttftMs,
                          UpstreamException error) {
    }

    private SseRelay() {
    }

    public static Outcome relay(UpstreamStream upstream, OutputStream out, ObjectMapper mapper, String requestId,
                                long requestStartNanos) {
        StringBuilder content = new StringBuilder();
        Usage usage = null;
        String finishReason = null;
        Long ttft = null;
        String model = null;
        try {
            while (true) {
                String data;
                try {
                    data = upstream.nextData();
                } catch (UpstreamException e) {
                    writeError(out, mapper, requestId, model, e.getMessage());
                    return new Outcome(Status.STREAM_ERROR, content.toString(), finishReason, usage, ttft, e);
                }
                if (data == null) {
                    if (finishReason != null) {
                        return new Outcome(Status.COMPLETED, content.toString(), finishReason, usage, ttft, null);
                    }
                    UpstreamException e = UpstreamException.of(UpstreamException.Kind.STREAM_BROKEN,
                            "provider closed the stream before it finished");
                    writeError(out, mapper, requestId, model, e.getMessage());
                    return new Outcome(Status.STREAM_ERROR, content.toString(), null, usage, ttft, e);
                }
                if ("[DONE]".equals(data)) {
                    return new Outcome(Status.COMPLETED, content.toString(), finishReason, usage, ttft, null);
                }

                JsonNode chunk;
                try {
                    chunk = mapper.readTree(data);
                } catch (IOException e) {
                    UpstreamException bad = UpstreamException.of(UpstreamException.Kind.MALFORMED,
                            "provider sent a malformed stream chunk", e);
                    writeError(out, mapper, requestId, model, bad.getMessage());
                    return new Outcome(Status.STREAM_ERROR, content.toString(), finishReason, usage, ttft, bad);
                }
                if (chunk.has("error")) {
                    String message = chunk.path("error").path("message").asText("provider reported an error mid-stream");
                    writeError(out, mapper, requestId, model, message);
                    return new Outcome(Status.STREAM_ERROR, content.toString(), finishReason, usage, ttft,
                            UpstreamException.of(UpstreamException.Kind.STREAM_BROKEN, message));
                }
                if (model == null && chunk.path("model").isTextual()) {
                    model = chunk.get("model").asText();
                }
                Usage u = Usage.from(chunk.get("usage"));
                if (u != null) {
                    usage = u;
                }
                JsonNode choice = chunk.path("choices").path(0);
                JsonNode delta = choice.path("delta").path("content");
                if (delta.isTextual() && !delta.asText().isEmpty()) {
                    content.append(delta.asText());
                    if (ttft == null) {
                        ttft = (System.nanoTime() - requestStartNanos) / 1_000_000;
                    }
                }
                if (choice.path("finish_reason").isTextual()) {
                    finishReason = choice.get("finish_reason").asText();
                }
                writeData(out, data); // forwarded exactly as the provider sent it
            }
        } catch (IOException clientGone) {
            // The client disconnected: stop the provider too, so we (and the tenant) stop paying for tokens.
            upstream.close();
            return new Outcome(Status.CLIENT_GONE, content.toString(), finishReason, usage, ttft, null);
        }
    }

    public static void writeData(OutputStream out, String data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** An SSE comment line: ignored by every SSE parser, handy for metadata such as the final cost. */
    public static void writeComment(OutputStream out, String comment) throws IOException {
        out.write((": " + comment + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static void writeDone(OutputStream out) throws IOException {
        writeData(out, "[DONE]");
    }

    static void writeError(OutputStream out, ObjectMapper mapper, String requestId, String model, String message)
            throws IOException {
        ObjectNode event = mapper.createObjectNode();
        event.put("id", requestId);
        event.put("object", "chat.completion.chunk");
        event.put("created", System.currentTimeMillis() / 1000);
        if (model != null) {
            event.put("model", model);
        }
        ArrayNode choices = event.putArray("choices");
        ObjectNode choice = choices.addObject();
        choice.put("index", 0);
        choice.putObject("delta");
        choice.put("finish_reason", "error");
        ObjectNode error = event.putObject("error");
        error.put("message", "The provider failed mid-stream (" + message + "). The response above is incomplete; "
                + "it was not retried elsewhere to avoid mixing two models' output.");
        error.put("type", PrismError.UPSTREAM_ERROR.type());
        error.put("code", "stream_interrupted");
        writeData(out, event.toString());
    }

    /** Replays a cached completion as a proper SSE stream (role chunk, content chunks, final chunk). */
    public static void replay(OutputStream out, ObjectMapper mapper, String requestId, JsonNode completion)
            throws IOException {
        String model = completion.path("model").asText("cache");
        long created = System.currentTimeMillis() / 1000;
        writeData(out, chunk(mapper, requestId, model, created, delta -> delta.put("role", "assistant").put("content", ""), null).toString());
        String content = completion.path("choices").path(0).path("message").path("content").asText("");
        for (String piece : content.split("(?<= )")) {
            writeData(out, chunk(mapper, requestId, model, created, delta -> delta.put("content", piece), null).toString());
        }
        ObjectNode last = chunk(mapper, requestId, model, created, delta -> { }, "stop");
        ObjectNode usage = last.putObject("usage");
        usage.put("prompt_tokens", 0).put("completion_tokens", 0).put("total_tokens", 0);
        writeData(out, last.toString());
    }

    private static ObjectNode chunk(ObjectMapper mapper, String id, String model, long created,
                                    java.util.function.Consumer<ObjectNode> delta, String finishReason) {
        ObjectNode c = mapper.createObjectNode();
        c.put("id", id).put("object", "chat.completion.chunk").put("created", created).put("model", model);
        ObjectNode choice = c.putArray("choices").addObject();
        choice.put("index", 0);
        delta.accept(choice.putObject("delta"));
        if (finishReason == null) {
            choice.putNull("finish_reason");
        } else {
            choice.put("finish_reason", finishReason);
        }
        return c;
    }
}
