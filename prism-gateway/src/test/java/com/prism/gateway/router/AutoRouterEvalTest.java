package com.prism.gateway.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.prism.gateway.TestFixtures;
import com.prism.gateway.embedding.EmbeddingService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the labelled routing sets through the router. The labels are only used here to score; the
 * router never sees them.
 */
class AutoRouterEvalTest {

    private static AutoRouter router;

    @BeforeAll
    static void load() throws Exception {
        EmbeddingService embeddings = new EmbeddingService();
        router = new AutoRouter(embeddings, new ExemplarIndex(embeddings, TestFixtures.MAPPER), TestFixtures.props());
    }

    private static List<JsonNode> cases(String file) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(TestFixtures.DATA_DIR.resolve(file))) {
            if (!line.isBlank()) {
                out.add(TestFixtures.MAPPER.readTree(line));
            }
        }
        return out;
    }

    private static double accuracy(String file) throws Exception {
        List<JsonNode> cases = cases(file);
        long correct = cases.stream().filter(c -> tier(c.get("prompt").asText()).equals(c.get("expected_tier").asText())).count();
        return (double) correct / cases.size();
    }

    private static String tier(String prompt) {
        return Map.of(AutoRouter.SIMPLE, "fast", AutoRouter.COMPLEX, "smart").get(router.classifyUncached(prompt).difficulty());
    }

    @Test
    void gradedEvalSetBeatsTheLengthBaseline() throws Exception {
        assertThat(accuracy("routing_eval.jsonl")).isGreaterThanOrEqualTo(0.90);
    }

    @Test
    void everyTrapInTheEvalSetIsRoutedCorrectly() throws Exception {
        for (JsonNode c : cases("routing_eval.jsonl")) {
            if (c.get("note").asText().startsWith("trap")) {
                assertThat(tier(c.get("prompt").asText())).as(c.get("id").asText()).isEqualTo(c.get("expected_tier").asText());
            }
        }
    }

    @Test
    void generalisesToPromptsItWasNotTunedOn() throws Exception {
        assertThat(accuracy("routing_heldout_v2.jsonl")).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void generalisesToASetWrittenOutsideTheProject() throws Exception {
        // Written during an external review, with deliberate keyword traps. 28/32 on its first (blind)
        // run; 29/32 after the "find the <superlative>" fix that its j22 miss motivated.
        assertThat(accuracy("routing_heldout_external.jsonl")).isGreaterThanOrEqualTo(0.85);
    }

    @Test
    void decisionsAreCachedAndExplainable() {
        RouteDecision first = router.classify("Prove that the square root of 2 is irrational.");
        RouteDecision second = router.classify("Prove that the square root of 2 is irrational.");
        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(second.reason()).contains("proof").contains("decision cache hit");
    }

    @Test
    void routingIsFastEnoughForTheHotPath() {
        router.classifyUncached("warm up the model");
        RouteDecision d = router.classifyUncached("Design a rate limiter for a multi-tenant API and explain the trade-offs.");
        assertThat(d.micros()).isLessThan(50_000); // < 50 ms, typically ~5 ms
    }

}
