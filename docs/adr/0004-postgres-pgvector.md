# ADR 0004: PostgreSQL + pgvector as the single durable store

**Status:** accepted (the one-page plan proposed MySQL + ChromaDB/FAISS)

## Context
We must persist keys, prices, usage, request logs and cache entries (the brief requires cache
entries in a reliable persistent store; FAISS is in-memory, ChromaDB is one more service).

## Decision
One PostgreSQL 16 database with the pgvector extension. Schema via Flyway (`prism-common`),
applied by whichever service starts first (Flyway takes an advisory lock).

## Consequences
- Tenant isolation of the cache is a `WHERE tenant_id = ?` in the same query that does the vector
  search; there is no separate vector store to keep in sync.
- Budget admission uses Postgres row locking (ADR 0006); usage and logs commit in one transaction.
- Vector search is an exact scan inside one tenant+scope partition, which is fast at this scale.
  Above ~10k entries per tenant, add an HNSW index (pgvector 0.8 iterative scans handle the filter).
