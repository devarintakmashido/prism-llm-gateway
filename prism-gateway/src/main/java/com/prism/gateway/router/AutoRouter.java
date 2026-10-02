package com.prism.gateway.router;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.prism.common.Keys;
import com.prism.gateway.config.PrismProperties;
import com.prism.gateway.embedding.EmbeddingService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * The "auto" alias: decides whether a prompt is simple (cheap tier) or complex (strong tier), inside
 * the request path and in milliseconds. It never calls an LLM, so routing adds almost no latency
 * (an LLM judge would add a whole extra model round trip before the real one).
 *
 * <ol>
 *   <li>Extract the instruction from any pasted context (so long-but-trivial prompts are not "hard").</li>
 *   <li>Score task-type signals on the instruction (proof, design, debugging ... vs lookup, rewrite ...).</li>
 *   <li>Add a nearest-neighbour vote over labelled example prompts, using the shared embedding model.</li>
 *   <li>If the combined score is too close to call, use the configured default tier. The default is
 *       "complex": sending an easy prompt to the strong model costs a little money, while sending a
 *       hard prompt to the weak model costs a bad answer.</li>
 * </ol>
 * Decisions are cached by prompt hash, so a repeated question skips classification entirely.
 */
@Component
public class AutoRouter {

    public static final String SIMPLE = "simple";
    public static final String COMPLEX = "complex";

    private final EmbeddingService embeddings;
    private final ExemplarIndex exemplars;
    private final PrismProperties.Router settings;
    private final Cache<String, RouteDecision> decisions = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofHours(1))
            .build();

    public AutoRouter(EmbeddingService embeddings, ExemplarIndex exemplars, PrismProperties props) {
        this.embeddings = embeddings;
        this.exemplars = exemplars;
        this.settings = props.router();
    }

    public RouteDecision classify(String prompt) {
        long start = System.nanoTime();
        String cacheKey = Keys.sha256(prompt.strip().toLowerCase(Locale.ROOT));
        RouteDecision cached = decisions.getIfPresent(cacheKey);
        if (cached != null) {
            return cached.asCached((System.nanoTime() - start) / 1_000);
        }
        RouteDecision decision = decide(prompt, start);
        decisions.put(cacheKey, decision);
        return decision;
    }

    /** Classification without the decision cache, for the routing eval. */
    public RouteDecision classifyUncached(String prompt) {
        return decide(prompt, System.nanoTime());
    }

    private RouteDecision decide(String prompt, long start) {
        InstructionExtractor.Extracted extracted = InstructionExtractor.extract(prompt);
        TaskSignals.Result signals = TaskSignals.evaluate(extracted.instruction());
        ExemplarIndex.Vote vote = exemplars.vote(embeddings.embed(extracted.instruction()), settings.knnK());

        double score = signals.score() + settings.knnWeight() * vote.score();
        boolean uncertain = Math.abs(score) < settings.uncertainMargin();
        String difficulty = uncertain ? settings.uncertainTier() : (score > 0 ? COMPLEX : SIMPLE);

        List<String> neighbours = vote.neighbours().stream().limit(2)
                .map(n -> String.format(Locale.ROOT, "'%s' [%s %.2f]", abbreviate(n.text(), 60),
                        n.complex() ? COMPLEX : SIMPLE, n.similarity()))
                .toList();
        return new RouteDecision(difficulty, score, signals.score(), vote.score(), uncertain,
                signals.matches().stream().map(TaskSignals.Match::toString).toList(),
                neighbours, extracted.instruction(), false, (System.nanoTime() - start) / 1_000);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
