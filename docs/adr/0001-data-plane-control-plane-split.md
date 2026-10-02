# ADR 0001: Split into a data plane and a control plane

**Status:** accepted

## Context
The reviewer asked for microservices. A gateway has two very different workloads: the hot request
path (every LLM call, latency-sensitive, must scale horizontally) and administration (usage reports,
logs, key management, the console), which changes often and is read-heavy.

## Decision
Two Spring Boot services behind one nginx edge:
- **prism-gateway** (data plane): `/v1/chat/completions`, auth, limits, budgets, auto routing, cache,
  failover, streaming, metering. Stateless; all shared state is in Postgres and Redis.
- **prism-control-plane**: `/admin/*` APIs and the ops console.

This mirrors how Kong, Envoy and Portkey are built. We deliberately did **not** split further:
the embedding model runs inside the gateway (ADR 0003), because a network hop to a separate
embedding service would add latency to every request and add one more thing that can fail.

## Consequences
- The data plane keeps serving if the control plane is down (it only needs Postgres and Redis).
- Horizontal scaling: `docker compose up --scale gateway=2`; rate limits and budgets stay exact because
  they are enforced in Redis/Postgres, not in process memory.
- Both services share one database with clear ownership: the gateway writes request logs and usage;
  the control plane reads them and owns key changes. A shared database is a trade-off (not "pure"
  microservices) that we accept at this scale; an event stream (logs to Kafka/Redis Streams) is the
  next step if the services ever need independent storage.
