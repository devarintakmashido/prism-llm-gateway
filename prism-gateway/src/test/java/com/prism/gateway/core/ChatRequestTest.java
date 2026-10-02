package com.prism.gateway.core;

import com.prism.gateway.TestFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRequestTest {

    @Test
    void rejectsMalformedBodiesWithA400() {
        for (String bad : new String[]{"not json", "[1,2]", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "{\"model\":\"fast\",\"messages\":[]}", "{\"model\":\"fast\",\"messages\":[{\"content\":\"no role\"}]}",
                "{\"model\":\"fast\",\"stream\":\"yes\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"}) {
            assertThatThrownBy(() -> TestFixtures.chat(bad))
                    .isInstanceOfSatisfying(PrismException.class,
                            e -> assertThat(e.error()).isEqualTo(PrismError.INVALID_REQUEST));
        }
    }

    @Test
    void extractsTheLastUserTurnFromMultiTurnConversations() {
        ChatRequest r = TestFixtures.chat("""
                {"model":"fast","messages":[
                  {"role":"user","content":"What is connection pooling?"},
                  {"role":"assistant","content":"Reusing connections."},
                  {"role":"user","content":[{"type":"text","text":"What happens when the pool is exhausted?"}]}]}""");
        assertThat(r.lastUserText()).isEqualTo("What happens when the pool is exhausted?");
        assertThat(r.lastUserIndex()).isEqualTo(2);
        assertThat(r.stream()).isFalse();
    }

    @Test
    void readsOutputLimitsForBudgetEstimates() {
        ChatRequest r = TestFixtures.chat("""
                {"model":"fast","max_tokens":64,"messages":[{"role":"user","content":"one two three"}]}""");
        assertThat(r.maxOutputTokens()).isEqualTo(64);
        assertThat(r.estimatedPromptTokens()).isGreaterThanOrEqualTo(3);
    }
}
