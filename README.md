# Prism: LLM gateway with smart routing and a semantic cache

Prism is an OpenAI-compatible LLM gateway. Teams call one endpoint with a virtual key; Prism
authenticates them, enforces their rate limit and monthly budget, decides which model tier the prompt
deserves, answers repeated questions from a semantic cache, fails over between providers, streams
tokens end to end, and meters every token and every dollar.

It makes two AI decisions on every request, in-process and in milliseconds:

- **How hard is this prompt?** `"model": "auto"` routes proofs, designs and debugging to the strong tier
  and lookups, rewrites and translations to the cheap one, with no LLM call: ~5 ms.
- **Have we answered this already?** Paraphrased questions are served from a per-team cache
  (Hugging Face `all-MiniLM-L6-v2` embeddings + pgvector), guarded against look-alike prompts with different answers.

| | Result (full report: [docs/VERIFICATION_REPORT.md](docs/VERIFICATION_REPORT.md)) |
|---|---|
| Provided smoke test | 17 pass, 1 warn, 0 fail, and stays green on repeated runs |
| Provided load test | 0 over-admission; usage API matches the client's totals exactly (cost to the 6 decimals the load test prints) |
| Routing eval | **100%** on the graded set (length baseline 75%); **95%** on a fresh held-out set; **88%** blind on an external reviewer's trap set (91% after one rule fix) |
| Failure drill | provider down / slow / flaky / restored: 6/6 |
| Automated tests | 95 (unit + Testcontainers against real Postgres and Redis, including the full HTTP pipeline) |
| Gateway overhead | p50 2 ms, p95 12 ms |

---

## Contents

1. [Quick start](#quick-start)
2. [Architecture](#architecture)
3. [API](#api)
4. [Design decisions](#design-decisions)
5. [Verification](#verification)
6. [Demo walkthrough](#demo-walkthrough)
7. [Configuration](#configuration)
8. [Local development](#local-development)
9. [Project layout](#project-layout)
10. [Known limitations and next steps](#known-limitations-and-next-steps)

---

## Quick start

Requirements: Docker with Compose (give Docker Desktop at least 4 GB of RAM). Python 3.9+ only to run
the provided test scripts. Host ports **8080, 9001, 9002, 5432 and 6379** must be free (stop a local
PostgreSQL or Redis first).

```bash
git clone https://github.com/devarintakmashido/prism-llm-gateway.git
cd prism-llm-gateway
docker compose up -d --build        # first build downloads dependencies: a few minutes
curl -s localhost:8080/health       # {"status":"ok", ... "providers":{"alpha":"healthy","beta":"healthy"}}
```

This starts: the gateway, the control plane, the ops console, PostgreSQL + pgvector, Redis, the two
provided mock providers (`alpha` on :9001, `beta` on :9002) and an nginx edge on **:8080**.
The seed keys, price table and gateway config from `data/` are loaded automatically.

Try it:

```bash
curl -s localhost:8080/v1/chat/completions -D - \
  -H "Authorization: Bearer prism-sk-search-1a2b3c" -H "Content-Type: application/json" \
  -d '{"model":"fast","messages":[{"role":"user","content":"What is a message queue?"}]}'
```

The response carries `x-prism-provider: alpha/alpha-small`, `x-prism-cache: miss`,
`x-prism-fallback: false`, `x-prism-cost-usd: 0.000013950` and a `Server-Timing` breakdown.

**Ops console:** http://localhost:8080. Enter the admin token `prism-admin-dev-token`
(set `PRISM_ADMIN_TOKEN` to change it).

**Seeded keys** (from `data/seed_keys.json`):

| Team | Virtual key | Allowed | RPM | Monthly budget | Cache |
|---|---|---|---|---|---|
| search | `prism-sk-search-1a2b3c` | fast | 60 | $50 | on, 0.92 |
| research | `prism-sk-research-4d5e6f` | fast, smart, auto | 300 | $500 | off |
| free-tier | `prism-sk-free-7g8h9i` | fast | 10 | $5 | on, 0.85 |
| budget-demo | `prism-sk-budget-demo-0j1k2l` | fast | 60 | $0.00001 | off |

Run everything the graders run:

```bash
./scripts/verify.sh     # smoke x2, load test + reconciliation, cache pairs, routing evals, streaming, failure drill
```

---

## Architecture

```mermaid
flowchart LR
    C[Client apps<br/>OpenAI SDKs] -->|/v1/chat/completions| E[nginx edge :8080]
    O[Platform admin] -->|/ console, /admin/*| E
    E -->|/v1, /health, /admin/route| G[prism-gateway<br/>data plane]
    E -->|/admin/*, /| CP[prism-control-plane<br/>admin APIs + console]
    G -->|rate limits<br/>health snapshots| R[(Redis)]
    G -->|keys, budgets, logs,<br/>cache vectors| P[(PostgreSQL<br/>+ pgvector)]
    CP --> P
    CP --> R
    G -->|OpenAI wire format| A[mock alpha :9001]
    G --> B[mock beta :9002]
    subgraph G_in [inside the gateway]
      direction TB
      EM[all-MiniLM-L6-v2<br/>ONNX, in-process]
    end
```

**Two services, split where the workloads differ** ([ADR 0001](docs/adr/0001-data-plane-control-plane-split.md)):

- **prism-gateway (data plane).** The hot path. Stateless, so it scales horizontally
  (`docker compose up -d --scale gateway=2 && docker compose restart edge`, since nginx resolves the replicas at start): rate limits live in Redis and budgets in Postgres, so they stay exact across instances.
- **prism-control-plane.** Usage, logs, cache stats, fleet-wide provider health, key management, and the console.
  The data plane keeps serving if the control plane is down.
- The embedding model is deliberately **not** a separate service: a network hop would add latency and
  a failure point to every request ([ADR 0003](docs/adr/0003-in-process-embeddings.md)).

**Stack:** Java 21, Spring Boot 3.5 (Spring MVC on **virtual threads**, [ADR 0002](docs/adr/0002-spring-mvc-virtual-threads.md)),
PostgreSQL 16 + pgvector, Redis 7, Flyway, LangChain4j ONNX embeddings, JDK HttpClient, nginx, Docker Compose.

### Life of a request

```
POST /v1/chat/completions
 1. authenticate virtual key (SHA-256 lookup, cached 5 s) ............ 401 authentication_error
 2. validate body ..................................................... 400 invalid_request_error
 3. model/alias exists ................................................ 404 not_found_error
 4. on the key's allowlist ............................................ 403 model_not_allowed
 5. rate limit (Redis Lua sliding window) ............................. 429 rate_limit_exceeded
 6. "auto": classify difficulty -> fast | smart
 7. reserve estimated cost against the monthly budget (atomic SQL) .... 402 budget_exceeded
 8. semantic cache: exact hash -> vector search -> entity/polarity/order guards  hit: served, cost 0
 9. provider chain: retry transient errors, fail over on outages ...... 502 upstream_error / 504 upstream_timeout
10. meter cost from provider usage; settle budget + write log (1 tx); store in cache
```

Every request, including every rejection, writes exactly one request-log row, and every response carries
the `x-prism-*` headers ([ADR 0012](docs/adr/0012-request-pipeline-order.md)).

---

## API

### Data plane (OpenAI-compatible)

`POST /v1/chat/completions` accepts the OpenAI request body. Use an alias as the model: `fast`, `smart`
or `auto`. Unknown fields (temperature, tools, response_format, ...) are passed through to the provider.
Callers never see provider credentials.

Response headers on **every** response:

| Header | Meaning |
|---|---|
| `x-prism-provider` | who served it: `alpha/alpha-small`, `cache`, or `none` for rejections |
| `x-prism-cache` | `hit` or `miss` |
| `x-prism-fallback` | `true` only if a fallback provider served it |
| `x-prism-cost-usd` | cost of this request (9 decimals; `0` for cache hits and rejections; omitted on live streams) |
| `x-prism-route` | for `auto`: chosen tier, difficulty and score |
| `x-prism-cache-similarity` / `x-prism-cache-source` | on cache hits |
| `x-request-id`, `Server-Timing` | request id and per-stage timings |
| `x-ratelimit-limit-requests`, `x-ratelimit-remaining-requests`, `Retry-After` | rate-limit state |

Streaming (`"stream": true`) returns `text/event-stream`. Chunks are forwarded as the provider produces
them, followed by `: prism-cost-usd=<cost>` (an SSE comment) and `data: [DONE]`. If the provider fails
mid-stream, the client gets an error event with `finish_reason: "error"`, then `[DONE]`
([ADR 0010](docs/adr/0010-streaming.md)).

Errors are OpenAI-shaped and distinct per case:

```json
{"error": {"message": "Monthly budget of $0.00001 exhausted for this key ($0.00001605 spent in 2026-09). It resets on the 1st (UTC).",
           "type": "budget_exceeded", "code": "budget_exceeded", "param": null, "request_id": "req_..."}}
```

| Case | Status | `type` |
|---|---|---|
| malformed body / empty messages | 400 | `invalid_request_error` |
| missing, unknown or disabled key | 401 | `authentication_error` |
| monthly budget exhausted | 402 | `budget_exceeded` |
| model not on the key's allowlist | 403 | `model_not_allowed` |
| unknown model or alias | 404 | `not_found_error` |
| rate limit exceeded | 429 | `rate_limit_exceeded` |
| all providers failed | 502 | `upstream_error` |
| all providers timed out | 504 | `upstream_timeout` |

`GET /v1/models` lists the aliases the key may use. `GET /health` shows liveness and provider states.

### Admin APIs

All require `Authorization: Bearer <admin token>`.

| Endpoint | Purpose |
|---|---|
| `GET /admin/usage?key=...&from=...&to=...` | spend, tokens, requests, cache hits, fallbacks, budget status, breakdown by model and status. `from`/`to` accept `YYYY-MM-DD` or ISO timestamps; `key` or `team`; without either: every key |
| `GET /admin/usage/daily?team=...&days=14` | per-day requests, spend, cache hits |
| `GET /admin/logs?key=...&status=...&limit=50` | recent request log, newest first, with route reason, attempts, cache note |
| `GET /admin/cache/stats` | hits, misses, hit rate, entries and $ saved, per key and overall |
| `DELETE /admin/cache?team=...` | clear one key's cache |
| `GET /admin/providers/health` | fleet view: each gateway instance's view of each provider |
| `GET /admin/overview` | last 24 h: requests, spend, hit rate, fallbacks, latency and overhead p50/p95, auto split |
| `GET /admin/keys`, `POST /admin/keys` | list keys (masked); create a key (shown once) |
| `POST /admin/keys/{team}/rotate`, `/disable`, `/enable` | key lifecycle (takes effect within 5 s) |
| `POST /admin/route` `{"prompt": "..."}` | classify a prompt without calling a model (used by the routing eval) |

Example:

```bash
curl -s -H "Authorization: Bearer prism-admin-dev-token" \
  "localhost:8080/admin/usage?key=prism-sk-free-7g8h9i&from=2026-09-01&to=2026-09-30"
```

---

## Design decisions

Each has a short ADR in [docs/adr](docs/adr).

**Routing, the `auto` alias** ([ADR 0011](docs/adr/0011-auto-router.md)). An LLM judge would add a whole
model round trip before the real one. Prism classifies in-process instead, in ~5 ms:
(1) strip pasted context so long-but-trivial prompts aren't "hard"; (2) score ~25 task types (proof +3,
debugging +2, design +2 ... translation -3, rewrite -2.5, lookup -2); (3) add a nearest-neighbour vote over
80 labelled example prompts; (4) when the score is too close to call, use the strong tier (a wasted cent
beats a wrong answer). Decisions are cached, logged with their reasons, and returned in `x-prism-route`.

**Failover, retries, health** ([ADR 0009](docs/adr/0009-retries-failover-health.md)).
- Transient errors (500/502/504, dropped connections, garbage) are retried on the same provider with
  exponential backoff and jitter.
- Outages (503, 429, connect failure, timeout) fail over to the next provider immediately.
- A 400 is the caller's problem and goes straight back.
- Health combines live traffic with active probes: real one-token completions every second. The mock's `/health`
  says "ok" even while it returns 503 for every completion, so it is not trusted.
- Unhealthy providers move to the back of the chain; one successful probe after an outage brings traffic back.

**Timeouts.** Connect 1 s; first byte 2 s (per provider); stream idle 10 s; whole request 30 s. A hung
provider cannot hang a client.

**Cache scoping** ([ADR 0008](docs/adr/0008-semantic-cache.md)).
- Always scoped per key: every query filters on `tenant_id`.
- Also scoped by tier, conversation context (system prompt and earlier turns) and answer-shaping
  parameters (temperature, max_tokens, ...).
- Matching is exact hash first, then cosine similarity at the key's threshold, then three guards that can
  only turn a hit into a miss. They exist because we measured wrong hits above any sane threshold.
  - The **entity guard**: numbers, ids and names must match. The same question with a different id
    scores 0.94, and a roster with one name changed scores 0.98.
  - The **polarity guard**: both prompts must be negated the same way and must not sit on opposite sides
    of a group of opposites ({enable, turn on, ...} vs {disable, turn off, ...}, increase/reduce,
    advantages/disadvantages, ...). "Why should I *not* use Kafka?" scores 0.986 against "Why should I use Kafka?".
  - The **order guard**: the same entities, or the words around "to", "into" and "than", must not appear
    in the opposite order. "Convert Celsius to Fahrenheit" scores 0.995 against "Fahrenheit to Celsius".
- Never cached: errors, truncated answers, refusals, tool calls, and time-sensitive prompts
  ("current status of …"). Prompts over 150 words are exact-match only.

**Budgets and rate limits** ([ADR 0005](docs/adr/0005-rate-limiting.md), [ADR 0006](docs/adr/0006-budgets.md)).
- Rate limits: a Redis sliding window updated by one atomic Lua script.
- Budgets: *reserve-then-settle*. One SQL statement reserves the worst-case cost only if
  `spent + reserved < budget`, so concurrent requests queue on the row instead of over-admitting.
- The real cost replaces the reservation in the same transaction as the request log.
- Maximum overshoot: the cost of the last request admitted while budget remained.

**Metering** ([ADR 0007](docs/adr/0007-money-as-integer-nanos.md)). Cost comes only from provider-reported
usage × the price table. It is carried as integer nano-USD and rounded once, so headers, logs and usage
totals reconcile exactly.

**Streaming** ([ADR 0010](docs/adr/0010-streaming.md)).
- On a virtual thread, streaming is a plain loop (read, write, flush), with backpressure to the provider.
- Failures before the first token can still fail over. After tokens have been sent, we never splice
  another model's output into the answer.
- A client disconnect closes the provider connection, so nobody pays for unread tokens.

**Security.**
- Virtual keys are stored as SHA-256 hashes and shown masked (`prism-sk-sea…2b3c`).
- Provider keys are injected only on the upstream call, and redacted from provider error messages.
- The admin API needs a separate token, compared in constant time.
- Prompt and response bodies are never written to the request log. The cache stores prompt and answer
  text per tenant, with a 24 h TTL.

---

## Verification

| What | Command |
|---|---|
| everything below, saved to `reports/` | `./scripts/verify.sh` |
| provided smoke test | `python3 scripts/smoke_test.py --url http://localhost:8080 --key prism-sk-search-1a2b3c --model fast` |
| provided load test + reconciliation against the usage API | `python3 scripts/reconcile.py --wait-for-window` |
| cache behaviour on the provided pairs and six adversarial pairs (opposites, reversed order) | `python3 scripts/cache_pairs.py` |
| routing eval (graded set) | `python3 scripts/routing_eval.py` |
| routing on held-out sets | `python3 scripts/routing_eval.py --cases data/routing_heldout_v2.jsonl` (also `routing_heldout.jsonl`, `routing_heldout_external.jsonl`) |
| failure drill | `python3 scripts/failure_drill.py` |
| two gateway replicas share one rate limit | `docker compose up -d --scale gateway=2 && docker compose restart edge && python3 scripts/two_replicas_demo.py` |
| unit + integration tests (95) | `mvn test` (the integration tests start Postgres and Redis with Testcontainers) |
| CI | `.github/workflows/ci.yml` runs `mvn verify`, then the stack and `verify.sh` |

Results, with output: **[docs/VERIFICATION_REPORT.md](docs/VERIFICATION_REPORT.md)**.

---

## Demo walkthrough

The recommended demo flow, step by step (all commands against `localhost:8080`):

```bash
chat() { curl -s -D - -o /dev/null localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" -H "Authorization: Bearer $1" -d "$2" | grep -i '^x-prism'; }

# 1. healthy platform
curl -s localhost:8080/health

# 2. non-streaming request: provider, cache miss, cost
chat prism-sk-search-1a2b3c '{"model":"fast","messages":[{"role":"user","content":"How do I reset my password on the dashboard?"}]}'

# 3. streaming, token by token (-N disables curl buffering)
curl -N localhost:8080/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer prism-sk-search-1a2b3c" \
  -d '{"model":"fast","stream":true,"messages":[{"role":"user","content":"Explain how server-sent events work."}]}'

# 4. repeat + paraphrase: both hits
chat prism-sk-search-1a2b3c '{"model":"fast","messages":[{"role":"user","content":"How do I reset my password on the dashboard?"}]}'
chat prism-sk-search-1a2b3c '{"model":"fast","messages":[{"role":"user","content":"What are the steps to reset my dashboard password?"}]}'

# 5. auto routing: short-but-hard -> smart, long-but-trivial -> fast; then the eval
chat prism-sk-research-4d5e6f '{"model":"auto","messages":[{"role":"user","content":"Prove that the square root of 2 is irrational."}]}'
python3 scripts/routing_eval.py

# 6. burst past the free tier's 10 rpm
python3 scripts/load_test.py --url http://localhost:8080 --key prism-sk-free-7g8h9i --model fast --requests 30 --concurrency 10 --rpm-limit 10

# 7. budget: first request passes, second is rejected (use a normal-length prompt; "hi" costs less than the tiny budget)
chat prism-sk-budget-demo-0j1k2l '{"model":"fast","messages":[{"role":"user","content":"What is a message queue and when should I use one?"}]}'
chat prism-sk-budget-demo-0j1k2l '{"model":"fast","messages":[{"role":"user","content":"What is a message queue and when should I use one?"}]}'

# 8. take alpha down live: served by beta with x-prism-fallback: true; then restore
curl -s -X POST localhost:9001/admin/config -d '{"mode":"down"}'
chat prism-sk-search-1a2b3c '{"model":"fast","messages":[{"role":"user","content":"What is a CDN?"}]}'
curl -s -X POST localhost:9001/admin/config -d '{"mode":"ok"}'

# 9. console: http://localhost:8080   10. ./scripts/verify.sh
```

The budget-demo key's budget ($0.00001) is smaller than one normal request ($0.000016), so the first
request goes through and the second is rejected. A one-word prompt costs only $0.000008, which is why
step 7 uses a normal question.

---

## Configuration

Copy `.env.example` to `.env` to override the admin token or add an OpenRouter key; docker compose reads it automatically.

| Variable | Default | Purpose |
|---|---|---|
| `PRISM_ADMIN_TOKEN` | `prism-admin-dev-token` | admin API / console token (change it outside local demos) |
| `PRISM_DB_URL`, `PRISM_DB_USER`, `PRISM_DB_PASSWORD` | local Postgres | database |
| `PRISM_REDIS_HOST`, `PRISM_REDIS_PORT` | `localhost:6379` | Redis |
| `PRISM_DATA_DIR` | `data` | where the seed files and gateway config live |
| `PRISM_ALPHA_BASE_URL`, `PRISM_ALPHA_API_KEY` (same for `BETA`) | from the config file | provider endpoint and credential overrides |
| `PRISM_INSTANCE_ID` | hostname | gateway instance name in logs and health |
| `OPENROUTER_API_KEY` (or `OPENAI_API_KEY`), `OPENROUTER_BASE_URL` (or `OPENAI_BASE_URL`) | empty = disabled | optional real provider, see below |

### Optional real provider: OpenRouter

The two mocks are the complete, graded setup. With an [OpenRouter](https://openrouter.ai) key, a real hosted
provider is added as the **last fallback** of the `fast` and `smart` chains (`alpha`, then `beta`, then
OpenRouter), so normal traffic and every verification step are unchanged; it serves requests only when both
mocks have failed.

```bash
cp .env.example .env            # then set OPENROUTER_API_KEY=... in .env (git-ignored)
docker compose up -d            # recreates the gateway; /health now lists "openrouter"
python3 scripts/openrouter_check.py   # takes both mocks down, expects OpenRouter to answer, restores them
```

| Prism model | Upstream model | Price (USD per 1M tokens in / out) |
|---|---|---|
| `openrouter-small` (end of `fast`) | `openai/gpt-4.1-nano` | 0.10 / 0.40 |
| `openrouter-large` (end of `smart`) | `openai/gpt-4.1-mini` | 0.40 / 1.60 |

How it plugs in, with no change to the provided files: the provider, its model-id mapping and its chain
position are in `data/gateway_extras.json`, merged into `gateway_config.sample.json` only when a key is set
(otherwise the file is ignored and the gateway logs "not enabled"). Prism's logical names are mapped to the
provider's own model ids on the upstream call, so callers still never see provider model names. The active health
probe is switched off for this provider (every probe would be a billed request), so its health comes from live
traffic only, and its first-byte timeout is 20 s because a hosted reasoning model starts answering later than a mock.
Costs come from the usage object OpenRouter returns, at the list prices above (`data/model_pricing.json`).

Tunables in `prism-gateway/src/main/resources/application.yml`: timeouts, cache TTL and eligibility,
router weights, probe interval. Providers, aliases, fallback chains, retry and degradation policy
come from `data/gateway_config.sample.json`, unchanged.

**Data added to the pack** (documented as the brief requires):
- `prism-gateway/src/main/resources/router/exemplars.jsonl`: 80 labelled example prompts for the router's
  nearest-neighbour vote, written independently of the eval set.
- `data/routing_heldout_external.jsonl`: 32 prompts written during an external review, with deliberate
  keyword traps (88% on its blind first run, 91% after one rule fix; see ADR 0011).
- `data/routing_heldout.jsonl` and `data/routing_heldout_v2.jsonl`: held-out routing sets (30 + 20) used only
  to measure generalisation. They do not affect the graded eval.

---

## Local development

```bash
docker compose up -d postgres redis mock-alpha mock-beta
mvn -q -DskipTests package
java -jar prism-gateway/target/prism-gateway.jar          # :8080 (serves /v1 and /admin/route)
PORT=8081 java -jar prism-control-plane/target/prism-control-plane.jar   # :8081 (admin APIs, console)
mvn test                                                  # 95 tests; needs Docker for Testcontainers
```

---

## Project layout

```
prism-common/          Flyway schema, seed loader, key hashing, money helpers
prism-gateway/         data plane
  api/                 /v1/chat/completions, /v1/models, /health, /admin/route
  core/                ChatPipeline (the request lifecycle), SSE relay, request trace + log writer
  tenant/ limits/      virtual keys, rate limiter (Redis Lua), budgets (reserve/settle)
  router/              auto router: instruction extraction, task signals, exemplar kNN
  cache/ embedding/    semantic cache (pgvector), cache policy + entity/polarity/order guards, MiniLM embeddings
  routing/ provider/   failover, provider health + probes, OpenAI-compatible adapter
prism-control-plane/   usage, logs, cache, providers, keys APIs + ops console (static/index.html)
docker/                Dockerfile (both services), mock provider image, nginx edge
data/                  provided seed data + added held-out routing sets
scripts/               provided scripts + verify.sh, reconcile.py, routing_eval.py, cache_pairs.py, failure_drill.py
docs/                  ADRs, verification report, starter docs
.github/workflows/     CI: tests, then the Docker stack and the verification scripts
```

---

## Known limitations and next steps

- **Pair B paraphrase misses.** Its cosine similarity is below the near miss A3's in every model we tried.
  Next step: a cross-encoder verifier over candidates above ~0.7 ([ADR 0008](docs/adr/0008-semantic-cache.md)).
- **The polarity and order guards are rules, not understanding.** Measured after the last fix, two kinds of
  wrong hit still get through: an opposite missing from the word list ("make my emails shorter" vs "longer",
  0.93, above the free tier's threshold) and a reversal the word-pair rule cannot see ("from s3 to my old laptop"
  vs "from my old laptop to s3", 0.995: a two-word name after "to"). The other way round, a question with a
  clause moved ("Using Terraform, create an S3 bucket" vs "create an S3 bucket with Terraform") loses its saving.
  The same cross-encoder verifier is the general fix.
- **Router is rule and example based.** Next step: label a few hundred prompts offline with a strong LLM
  to grow the exemplar bank or fit the weights (the RouteLLM approach), with no LLM on the hot path.
- **Rate limiter fails open** if Redis is unreachable (budgets still hold).
- **Crash leaves a budget reservation** until cleared (fix: reservation ledger with expiry).
- **Per-key budget admissions serialise** on the key's usage row (correct, but ~tens of ms under heavy
  concurrent bursts from one key). Sharded counters would lift that.
- **Tokens-per-minute limits** are stored per key but not enforced yet.
- **One shared database** for both services; an event stream for logs is the next step if they need independent storage.
- **No real provider by default.** OpenRouter is wired in as an optional last fallback (see Configuration) and is
  verified live with `scripts/openrouter_check.py`, but it is not part of the automated run: it needs a key and
  spends money. Any other OpenAI-compatible API (OpenAI, Groq, Ollama) plugs in the same way: base URL, key,
  model-id mapping and chain position in `data/gateway_extras.json`.
