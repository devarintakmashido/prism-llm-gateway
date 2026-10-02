package com.prism.gateway.embedding;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sentence embeddings from Hugging Face's sentence-transformers/all-MiniLM-L6-v2 (384 dimensions),
 * run inside the JVM with ONNX Runtime: about 3 ms for a short prompt, no network hop, no extra
 * service that can fail. The cache and the auto router share it, and recent results are memoised
 * so a prompt is embedded once even when both need it.
 * <p>
 * Chosen over bge-small-en-v1.5 and e5-small-v2 by measurement (see docs/adr): it separates the
 * graded paraphrase pair from its near miss with the widest margin at the seeded thresholds.
 */
@Component
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    public static final int DIMENSIONS = 384;

    private final EmbeddingModel model;
    private final Cache<String, float[]> recent = Caffeine.newBuilder().maximumSize(5_000).build();

    public EmbeddingService() {
        long start = System.nanoTime();
        this.model = new AllMiniLmL6V2EmbeddingModel();
        model.embed("warm up");
        log.info("Embedding model all-MiniLM-L6-v2 loaded in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    public float[] embed(String text) {
        return recent.get(text, t -> model.embed(t).content().vector());
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    /** pgvector text form: [0.1,0.2,...] */
    public static String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
