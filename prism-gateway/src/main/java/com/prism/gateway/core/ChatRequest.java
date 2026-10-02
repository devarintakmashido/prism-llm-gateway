package com.prism.gateway.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A validated OpenAI chat-completions request. The original JSON is kept as-is so unknown fields
 * (temperature, tools, response_format ...) pass through to the provider untouched.
 */
public final class ChatRequest {

    public record Message(String role, String text) {
    }

    private final ObjectNode body;
    private final String model;
    private final boolean stream;
    private final List<Message> messages;

    private ChatRequest(ObjectNode body, String model, boolean stream, List<Message> messages) {
        this.body = body;
        this.model = model;
        this.stream = stream;
        this.messages = messages;
    }

    /** Best-effort read of the "model" field, for logging requests that are rejected before validation. */
    public static String peekModel(byte[] raw, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(raw);
            return node != null && node.path("model").isTextual() ? node.get("model").asText() : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static ChatRequest parse(byte[] raw, ObjectMapper mapper) {
        JsonNode node;
        try {
            node = mapper.readTree(raw);
        } catch (IOException e) {
            throw invalid("Request body is not valid JSON.");
        }
        if (!(node instanceof ObjectNode body)) {
            throw invalid("Request body must be a JSON object.");
        }
        JsonNode model = body.get("model");
        if (model == null || !model.isTextual() || model.asText().isBlank()) {
            throw invalid("'model' is required: use an alias such as \"fast\", \"smart\" or \"auto\".");
        }
        JsonNode messages = body.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw invalid("'messages' must be a non-empty array.");
        }
        List<Message> parsed = new ArrayList<>();
        for (JsonNode m : messages) {
            if (!m.isObject() || !m.path("role").isTextual()) {
                throw invalid("Every message needs a string 'role'.");
            }
            parsed.add(new Message(m.get("role").asText(), text(m.get("content"))));
        }
        JsonNode stream = body.get("stream");
        if (stream != null && !stream.isBoolean() && !stream.isNull()) {
            throw invalid("'stream' must be a boolean.");
        }
        return new ChatRequest(body, model.asText(), stream != null && stream.asBoolean(), List.copyOf(parsed));
    }

    /** Message content is either a string or an array of parts; we only need the text parts. */
    private static String text(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if (part.path("text").isTextual()) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(part.get("text").asText());
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private static PrismException invalid(String message) {
        return new PrismException(PrismError.INVALID_REQUEST, message);
    }

    public ObjectNode body() {
        return body;
    }

    public String model() {
        return model;
    }

    public boolean stream() {
        return stream;
    }

    public List<Message> messages() {
        return messages;
    }

    /** The last user turn: what the router classifies and what the cache matches on. */
    public String lastUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).text();
            }
        }
        return messages.getLast().text();
    }

    /** Index of the last user turn; everything before it is "context" for cache scoping. */
    public int lastUserIndex() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return i;
            }
        }
        return messages.size() - 1;
    }

    /** Rough prompt size for budget reservations: max(words, chars / 4) plus per-message overhead. */
    public long estimatedPromptTokens() {
        long tokens = 0;
        for (Message m : messages) {
            String t = m.text();
            long words = t.isBlank() ? 0 : t.strip().split("\\s+").length;
            tokens += Math.max(words, t.length() / 4) + 4;
        }
        return tokens;
    }

    /** Caller's max_tokens / max_completion_tokens, if set. */
    public Integer maxOutputTokens() {
        if (body.path("max_completion_tokens").canConvertToInt()) {
            return body.get("max_completion_tokens").asInt();
        }
        if (body.path("max_tokens").canConvertToInt()) {
            return body.get("max_tokens").asInt();
        }
        return null;
    }
}
