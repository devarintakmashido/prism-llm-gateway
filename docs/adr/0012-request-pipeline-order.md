# ADR 0012: Order of checks in the request pipeline

**Status:** accepted

1. authenticate the virtual key: `401 authentication_error`
2. validate the body: `400 invalid_request_error`
3. the model or alias must exist: `404 not_found_error`
4. and be on the key's allowlist: `403 model_not_allowed`
5. rate limit: `429 rate_limit_exceeded` (+ `Retry-After`)
6. `auto`: classify difficulty and pick an allowed tier
7. budget reservation: `402 budget_exceeded`
8. semantic cache lookup
9. providers, with retries and failover: `502 upstream_error` / `504 upstream_timeout`
10. meter, cache, log (one transaction with the budget settle)

Rationale: cheap, stateless checks first. Nothing that consumes a limit happens before a request could
be rejected for free. Rate limiting comes before the budget so a flood of requests from an exhausted
key still costs no database writes. Budget comes before the cache so an exhausted key is told so
consistently, as the API contract orders it; cache hits are free and settle to zero.

Every request, including every rejection, writes exactly one request-log row. Every response, including
errors, carries the `x-prism-*` headers (`x-prism-provider: none`, `x-prism-cost-usd: 0` for rejections).
