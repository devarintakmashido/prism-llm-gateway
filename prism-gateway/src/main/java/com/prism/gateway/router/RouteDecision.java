package com.prism.gateway.router;

import java.util.List;

/**
 * Why the auto router picked a tier. Everything here is logged with the request so a reviewer can
 * see exactly which signals and which neighbours drove the decision.
 *
 * @param difficulty   "simple" or "complex" (mapped to an alias by route_by_difficulty in the config)
 * @param score        signalScore + knnWeight * knnScore; positive means complex
 * @param uncertain    |score| fell inside the uncertainty margin, so the configured default tier was used
 * @param cached       served from the decision cache (an identical prompt was classified before)
 * @param micros       classification time
 */
public record RouteDecision(String difficulty,
                            double score,
                            double signalScore,
                            double knnScore,
                            boolean uncertain,
                            List<String> signals,
                            List<String> neighbours,
                            String instruction,
                            boolean cached,
                            long micros) {

    public String reason() {
        StringBuilder sb = new StringBuilder();
        sb.append(difficulty).append(String.format(" (score %+.2f", score));
        if (uncertain) {
            sb.append(", uncertain -> default tier");
        }
        sb.append("): signals [").append(String.join(", ", signals)).append("]");
        sb.append(String.format("; knn %+.2f", knnScore));
        if (!neighbours.isEmpty()) {
            sb.append(" nearest ").append(neighbours.getFirst());
        }
        if (cached) {
            sb.append("; decision cache hit");
        }
        return sb.toString();
    }

    public RouteDecision asCached(long lookupMicros) {
        return new RouteDecision(difficulty, score, signalScore, knnScore, uncertain, signals, neighbours,
                instruction, true, lookupMicros);
    }
}
