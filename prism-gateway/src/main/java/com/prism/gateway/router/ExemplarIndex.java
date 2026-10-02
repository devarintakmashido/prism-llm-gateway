package com.prism.gateway.router;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.gateway.embedding.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Nearest-neighbour vote over a bank of labelled example prompts (router/exemplars.jsonl), embedded
 * once at startup. This is the router's "learned" component: it generalises to phrasings the
 * keyword signals do not cover.
 * <p>
 * The bank is written independently of data/routing_eval.jsonl. The eval set is the answer key
 * and is never used by the router.
 */
@Component
public class ExemplarIndex {

    private static final Logger log = LoggerFactory.getLogger(ExemplarIndex.class);

    public record Exemplar(String text, boolean complex, float[] vector) {
    }

    public record Neighbour(String text, boolean complex, double similarity) {
    }

    /** score in [-1, 1]: similarity-weighted vote of the k nearest examples (+1 complex, -1 simple). */
    public record Vote(double score, List<Neighbour> neighbours) {
    }

    private final EmbeddingService embeddings;
    private final List<Exemplar> exemplars;

    public ExemplarIndex(EmbeddingService embeddings, ObjectMapper mapper) throws IOException {
        this.embeddings = embeddings;
        this.exemplars = load(mapper);
        log.info("Router exemplar bank: {} examples ({} complex)", exemplars.size(),
                exemplars.stream().filter(Exemplar::complex).count());
    }

    public Vote vote(float[] query, int k) {
        List<Neighbour> nearest = exemplars.stream()
                .map(e -> new Neighbour(e.text(), e.complex(), EmbeddingService.cosine(query, e.vector())))
                .sorted(Comparator.comparingDouble(Neighbour::similarity).reversed())
                .limit(k)
                .toList();
        double weighted = 0, total = 0;
        for (Neighbour n : nearest) {
            double w = Math.max(n.similarity(), 0);
            weighted += w * (n.complex() ? 1 : -1);
            total += w;
        }
        return new Vote(total == 0 ? 0 : weighted / total, nearest);
    }

    public int size() {
        return exemplars.size();
    }

    private List<Exemplar> load(ObjectMapper mapper) throws IOException {
        List<Exemplar> out = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource("router/exemplars.jsonl");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode row = mapper.readTree(line);
                String text = row.get("prompt").asText();
                boolean complex = "complex".equals(row.get("difficulty").asText());
                String instruction = InstructionExtractor.extract(text).instruction();
                out.add(new Exemplar(text, complex, embeddings.embed(instruction)));
            }
        }
        return List.copyOf(out);
    }
}
