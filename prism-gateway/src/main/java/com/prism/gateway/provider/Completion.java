package com.prism.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A successful non-streaming completion.
 *
 * @param body  the provider's response bytes, forwarded to the caller unchanged
 * @param json  the same body parsed, for usage and cache decisions
 * @param usage provider-reported usage, or null if the provider did not send it
 */
public record Completion(byte[] body, JsonNode json, Usage usage) {

    /** The assistant text of the first choice, or "" if there is none. */
    public String content() {
        return json.path("choices").path(0).path("message").path("content").asText("");
    }

    public String finishReason() {
        return json.path("choices").path(0).path("finish_reason").asText("");
    }
}
