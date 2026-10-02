# ADR 0003: Hugging Face embeddings inside the JVM (no Python sidecar)

**Status:** accepted (the one-page plan proposed a FastAPI sidecar)

## Context
Both AI decisions (semantic cache, auto router) need sentence embeddings on every request.

## Decision
Run `sentence-transformers/all-MiniLM-L6-v2` (384 dimensions) in-process with ONNX Runtime via
LangChain4j's embeddings module. Measured: ~3 ms for a short prompt, ~8 ms for a long one.
The model and native libraries ship inside the jar for linux-x64, linux-aarch64 and macOS, so
the Docker image works offline on Intel and Apple Silicon.

Model choice was measured, not assumed (spike over the provided cache pairs):

| pair | MiniLM-L6-v2 | bge-small-en-v1.5 | e5-small-v2 |
|---|---|---|---|
| A1~A2 paraphrase (should hit) | 0.964 | 0.982 | 0.979 |
| A1~A3 near miss (should miss) | 0.821 | 0.898 | 0.972 |
| B1~B2 paraphrase (should hit) | 0.732 | 0.887 | 0.915 |
| unrelated (TCP vs password) | 0.01 | 0.48 | 0.79 |

MiniLM separates A2 from A3 by the widest margin at the seeded thresholds (0.92 / 0.85).
bge would false-hit A3 at the free tier's 0.85; e5 compresses everything into 0.8-1.0.

## Consequences
- No extra service, no network hop, no Python runtime in production.
- Pair B cannot be hit without also hitting A3 by cosine alone (see ADR 0008).
- A larger model (or a cross-encoder re-ranker) can be swapped in behind `EmbeddingService`.
