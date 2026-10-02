# ADR 0002: Spring MVC on Java 21 virtual threads, not WebFlux

**Status:** accepted (changed from the one-page plan, which proposed WebFlux)

## Context
A gateway holds many concurrent, mostly idle connections: requests waiting on slow LLMs and long
streams. The classic answer is a reactive stack (WebFlux). Reactive code is harder to read, debug
and review, and blocking JDBC on an event loop is a well-known source of production incidents.

## Decision
Spring MVC with `spring.threads.virtual.enabled=true`. Every request, including a multi-minute
stream, runs on a virtual thread as plain blocking code: read a line from the provider, write it to
the client, flush. Blocking a virtual thread costs a few hundred bytes, not an OS thread.

## Consequences
- Streaming, retries, backoff (`Thread.sleep`) and timeouts are straightforward sequential code.
- JDBC and the Redis client are used directly, with no reactive drivers.
- Backpressure comes from the upstream subscriber requesting one line at a time
  (`LineQueue`), so a slow client slows the provider read instead of buffering tokens in memory.
- Known caveat: on JDK 21, code that blocks inside `synchronized` pins the carrier thread. The
  PostgreSQL driver avoids `synchronized` since 42.6; ONNX inference is short (~3 ms).
