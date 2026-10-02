package com.prism.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;

/** Token usage as reported by the provider. Costs are always computed from this, never from the caller. */
public record Usage(int promptTokens, int completionTokens) {

    public static final Usage ZERO = new Usage(0, 0);

    /** Reads an OpenAI "usage" object; returns null when it is missing or incomplete. */
    public static Usage from(JsonNode usage) {
        if (usage == null || !usage.isObject() || !usage.has("prompt_tokens") || !usage.has("completion_tokens")) {
            return null;
        }
        return new Usage(usage.get("prompt_tokens").asInt(), usage.get("completion_tokens").asInt());
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
