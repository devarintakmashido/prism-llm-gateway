# ADR 0009: Retries, failover and provider health

**Status:** accepted

## Retry vs fail over
| upstream outcome | action |
|---|---|
| 500, 502, 504, dropped connection, malformed body | retry the same provider with exponential backoff + jitter (config: 3 attempts, 200 ms, x2) |
| 503, 429, connection refused, first-byte timeout | fail over to the next provider immediately |
| 400 / 422 (the request itself is invalid) | stop; every provider would reject it |

Retries happen only before the first byte is sent to the client (ADR 0010).

## Timeouts
Connect 1 s; first byte 2 s (per provider, so a slow real model can have more); stream idle 10 s;
whole non-streaming request, retries included, 30 s. A hung provider can never hang a client.

## Health
The mock's `/health` endpoint answers "ok" even while every completion returns 503, so we do not trust it.
- **Passive**: every real request records success/failure/latency per provider over a 60 s window.
- **Active**: every second, each provider gets a one-token completion (the same code path as traffic),
  with at most one probe in flight per provider so a hung provider does not collect a pile of probes.
- UNHEALTHY after 3 consecutive failures or an error rate >= 50%; DEGRADED if p95 > 5 s. These values
  come from the `degradation` block of the provided config.
- Unhealthy and degraded providers are moved to the back of the chain, never removed, so a request still
  succeeds on them if everything else is failing.
- The first success after a failure streak clears the outage, so traffic returns to the primary
  within one probe interval (about 1 s; it was 3 s until a review pointed out that the brief's Must Have
  says "immediately"). A dead provider is marked UNHEALTHY after three failed probes, about 3 s.
- Each instance publishes its view to Redis; the control plane shows the fleet (`/admin/providers/health`).

Precedent: OpenRouter's default routing skips providers with significant outages in the last 30 seconds.
