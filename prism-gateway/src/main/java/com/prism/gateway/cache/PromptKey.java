package com.prism.gateway.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.common.Keys;
import com.prism.gateway.core.ChatRequest;

import java.util.List;
import java.util.Locale;

/**
 * How a request is keyed in the cache.
 * <ul>
 *   <li><b>Matched text</b>: the last user message. That is the question being asked.</li>
 *   <li><b>Scope</b>: the model tier, plus a hash of everything else that changes the answer: the
 *       earlier turns and system prompt, and generation parameters such as temperature or
 *       max_tokens. A follow-up question therefore only matches when the whole conversation before
 *       it is identical, and a max_tokens=5 request is never served a full-length cached answer.</li>
 *   <li><b>Semantic eligibility</b>: long prompts (pasted documents, logs) are exact-match only.
 *       A small change in a long context changes the answer but barely moves the embedding, and
 *       the model truncates its input at 256 tokens.</li>
 * </ul>
 */
public record PromptKey(String scopeKey, String promptHash, String text, int words, boolean semanticEligible) {

    private static final List<String> ANSWER_SHAPING_PARAMS = List.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens", "stop", "seed", "response_format",
            "presence_penalty", "frequency_penalty", "logit_bias", "logprobs", "top_logprobs", "reasoning_effort");

    public static PromptKey of(String tier, ChatRequest request, ObjectMapper mapper, int maxSemanticWords) {
        ObjectNode scope = mapper.createObjectNode();
        ArrayNode context = scope.putArray("context");
        int lastUser = request.lastUserIndex();
        List<ChatRequest.Message> messages = request.messages();
        for (int i = 0; i < messages.size(); i++) {
            if (i != lastUser) {
                context.addObject().put("role", messages.get(i).role()).put("text", messages.get(i).text());
            }
        }
        ObjectNode params = scope.putObject("params");
        for (String p : ANSWER_SHAPING_PARAMS) {
            JsonNode v = request.body().get(p);
            if (v != null && !v.isNull()) {
                params.set(p, v);
            }
        }
        String scopeKey = tier + ":" + Keys.sha256(scope.toString()).substring(0, 16);

        String text = request.lastUserText().strip();
        String normalised = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        int words = text.isEmpty() ? 0 : text.split("\\s+").length;
        return new PromptKey(scopeKey, Keys.sha256(normalised), text, words, words > 0 && words <= maxSemanticWords);
    }
}
