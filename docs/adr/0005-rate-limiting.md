# ADR 0005: Requests-per-minute limits with a Redis Lua sliding window

**Status:** accepted

## Decision
An exact sliding-window log per key: a Redis sorted set of request ids scored by arrival time.
One Lua script removes expired entries, counts, and admits or rejects, atomically. Time comes from
`redis.call('TIME')`, so gateway instances with different clocks share one consistent window.

## Why not the alternatives
- Read-then-write counters over-admit under concurrency (the load test is built to catch this).
- Fixed windows allow 2x bursts at the boundary; a sliding window does not.
- In-process limiters break as soon as there are two gateway instances.

## Consequences
- Verified: 50 concurrent requests against a 10 rpm key admit exactly 10 (unit test) and the
  provided load test shows zero over-admission.
- Rejected requests get `429 rate_limit_exceeded` plus `Retry-After`, `x-ratelimit-limit-requests`
  and `x-ratelimit-remaining-requests` headers.
- If Redis is unreachable we fail open and log a warning (Envoy's default), so a limiter outage
  does not take the gateway down. Budgets still apply because they live in Postgres.
