# Prism verification report

All results below come from the Dockerized stack (`docker compose up -d --build`, fresh volumes), built
from a clean copy of the repository (no `.env`, so the optional OpenRouter provider is off) and exercised
through the nginx edge on `http://localhost:8080` with the provided mock providers.
Every number can be reproduced with one command:

```bash
./scripts/verify.sh          # writes every output to reports/
```

Environment: MacBook Air M2 (8 GB), Docker Desktop VM with 2 CPUs / 2 GB RAM, Java 21, Spring Boot 3.5.

## Summary

| Check | Result |
|---|---|
| Provided smoke test, run 1 | **17 passed, 1 warning (pair B), 0 failed** |
| Provided smoke test, run 2 (new salt, warm cache) | **17 passed, 1 warning, 0 failed** (stays correct across runs) |
| Load test over-admission (free-tier key, limit 10) | **0**: 10 accepted, 20 rejected with 429 |
| Accounting reconciliation (client vs usage API) | **exact** for requests, prompt tokens and completion tokens; cost matches to the 6 decimals the provided load test prints |
| Routing eval, `data/routing_eval.jsonl` | **20/20 = 100%** (best length-only baseline: 75%) |
| Routing, fresh held-out set never tuned on | **19/20 = 95%** (length-only: 75%) |
| Routing, external reviewer's set with keyword traps | **29/32 = 91%** (88% on its blind first run; length-only: 72%) |
| Semantic cache on the provided pairs + 6 adversarial pairs | pair A hit, near miss A3 miss, pair B miss, `req_no_cache` bypassed, every opposite and reversed question missed: **0 false hits** on the tested pairs |
| Streaming through the edge proxy | progressive: 24 events spread over ~510 ms (not buffered) |
| Live failure drill (down / slow / flaky / restored) | **6/6**; traffic back on alpha 0.56 s after a restore |
| Automated tests (`mvn test`) | **95 passed** (71 unit, 24 against real Postgres + Redis via Testcontainers: the full HTTP pipeline, concurrency, cache isolation, the control-plane admin API) |
| Gateway added latency (sequential, Docker) | **p50 2 ms, p95 12 ms** |

## 1. Smoke test

`python3 scripts/smoke_test.py --url http://localhost:8080 --key prism-sk-search-1a2b3c --model fast`

```
[1] Non-streaming completion + header contract
  PASS  returns 200  (got 200)
  PASS  body has choices and usage
  PASS  header x-prism-provider present  (alpha/alpha-small)
  PASS  header x-prism-cache present  (miss)
  PASS  header x-prism-cost-usd present  (0.000014700)
  PASS  first request is a cache miss  (miss)
[2] Streaming
  PASS  returns 200  (got 200)
  PASS  content-type is text/event-stream  (text/event-stream;charset=UTF-8)
  PASS  received multiple SSE chunks  (22 data lines)
  PASS  stream ends with [DONE]
[3] Auth
  PASS  invalid key rejected with 401/403  (got 401)
[4] Unknown model
  PASS  unknown model rejected with 4xx  (got 404)
  PASS  error body explains the problem
[5] Semantic cache: exact repeat
  PASS  identical repeat is a cache hit  (hit)
[6] Semantic cache: paraphrases (each pair soft; at least one pair must hit)
  PASS  pair A paraphrase is a cache hit  (hit)
  WARN  pair B paraphrase is a cache hit  (miss)
  PASS  matching is semantic, not exact-only (at least one pair hit)
[7] Semantic cache: unrelated prompt
  PASS  unrelated prompt is a cache miss  (miss)
  17 passed, 1 warnings, 0 failed
```

**Why the second run matters.** The smoke test prefixes its prompts with a random id, e.g.
`(3fa9c2b1) What is a load balancer?`. Embeddings largely ignore that id: the same question with two
different ids scores **0.94** cosine similarity, above the 0.92 threshold. A cache that trusts cosine
alone therefore fails "first request is a cache miss" on every run after the first. Prism's entity
guard (numbers, ids and names must match, see ADR 0008) turns those into misses. The request log
shows it working: `entity guard rejected a 0.953 match ([2891183d, tcp, udp] vs [3a6f696a, tcp, udp])`.

**The pair B warning** is explained in section 3.

## 2. Load test and accounting reconciliation

`python3 scripts/reconcile.py` runs the provided load test (30 requests, concurrency 10, free-tier key
with a 10 requests/minute limit), then queries the usage API for the same key and time window:

```
Burst finished in 0.5s
  accepted (200):      10
  rate limited (429):  20
  latency avg/p95 ms:  500 / 514

Client-side accounting totals - compare with your usage API for this key:
  accepted requests:   10
  prompt tokens:       120
  completion tokens:   265
  sum of cost headers: 0.000177 USD (10 of 10 had a numeric header)

Over-admission check OK: 10 accepted <= limit 10.

Reconciliation for free-tier between 2026-10-02T16:34:59.644449+00:00 and 2026-10-02T16:35:00.864550+00:00
                          client (load test)       usage API   match
  accepted                                10              10   yes
  prompt_tokens                          120             120   yes
  completion_tokens                      265             265   yes
  cost_usd                       0.000177000     0.000177000   yes
  rejected by rate limiter (usage API): 20

Over-admission: 0 (accepted 10 with limit 10)
RESULT: PASS - totals reconcile exactly
```

Why it reconciles: costs are integer nano-USD, rounded once per request, and the same integer goes into
the header, the log and the usage total (ADR 0007), so the 9-decimal cost headers sum to the usage
total by construction (`CostCalculatorTest.sumsOfRoundedCostsMatchTheLedgerExactly`). The provided load
test prints its sum with only 6 decimals, so `reconcile.py` compares cost at 6 decimals; requests and tokens
are compared exactly. In this run the total ($0.000177000) has nothing beyond the sixth decimal, so the two
agree digit for digit; in an earlier run they read $0.000172 (client) and $0.0001718 (usage API). The log row and the budget
update are written in one transaction before the response is released (ADR 0006).

**Latency in this burst (avg 500 ms) is the mock provider, not the gateway.** Per-stage `Server-Timing`
headers show gateway stages of roughly 20-100 ms under the burst, with `upstream` taking ~1,150 ms on
a few requests. The provided mock is Python's `ThreadingHTTPServer`. Its listen backlog is 5
(`socketserver.TCPServer.request_queue_size`), and it speaks HTTP/1.0, so every request opens a new
connection. Measured directly against the mock, with no gateway involved:

```
 5 simultaneous requests straight to the mock: median 128 ms, max  129 ms, >900ms: 0
20 simultaneous requests straight to the mock: median   7 ms, max 3231 ms, >900ms: 9
```

Dropped connection requests are retried by TCP after 1 s (then 3 s), which is the tail seen in the load test.

### Concurrency tests against real infrastructure (Testcontainers)

| Test | Result |
|---|---|
| 50 concurrent requests, 10 rpm key | exactly 10 admitted |
| 20 concurrent requests, budget-demo key ($0.00001) | exactly 1 admitted, then `budget_exceeded`; no leaked reservations |
| 40 concurrent reserve+settle on one key | spend = 40 x cost exactly (no lost updates) |
| 30 concurrent first-of-month requests | all admitted (regression test for a race found during verification, see "Bugs found") |
| a negated question at cosine 0.986 | rejected by the polarity guard: no hit |
| a reversed conversion ("Fahrenheit to Celsius") above 0.92 | rejected by the order guard: no hit |
| an expired cache entry not yet purged | replaced by the next answer; a live entry is kept |

### The whole pipeline over HTTP

`ChatPipelineIntegrationTest` starts the gateway against real Postgres and Redis, with two in-process
fakes standing in for alpha and beta, and sends real `/v1/chat/completions` requests:

| Checked | Result |
|---|---|
| rejection order: no key, bad key (also with a bad model), bad body, unknown model, not on the allowlist (`smart`, `auto`, a raw provider model name) | 401, 401, 401, 400, 404, 403, 403, 403; one log row each; no provider called |
| a served request and its repeat | header contract, cost header = log `cost_nanos` (1,650); repeat is a free cache hit, provider not called again |
| a stream | headers up front without a cost, `: prism-cost-usd=0.000002850` then `[DONE]`, logged as a stream with its cost |
| free-tier key, 11 requests | 10 served, the 11th gets 429 with `Retry-After` |
| budget-demo key | served while spend was below the budget, then 402; overshoot below one request's cost |
| `auto` with a key that may not use `smart` | the proof routes to `fast` with the reason logged; a key with only `auto` gets 403 |
| alpha down, then both down | beta serves with `x-prism-fallback: true` and the 503 attempt logged; then a clean 502 |

`ChatPipelineClientGoneTest` covers what HTTP cannot time reliably: a client that disconnects during a
cached stream replay, or just before a response body, still leaves exactly one log row and settles its
budget reservation once.

## 3. Semantic cache on the provided sample pairs

Embedding model: `sentence-transformers/all-MiniLM-L6-v2` (384 dimensions, in-process ONNX). Thresholds
as seeded: search 0.92, free-tier 0.85. `python3 scripts/cache_pairs.py`:

```
search key (threshold 0.92)
  request       expectation                     x-prism-cache  log      best similarity  note
  req_cache_a1  store                           miss           miss     0.896            best similarity 0.896 < threshold 0.92
  req_cache_a2  paraphrase of a1                hit            hit      0.962            semantic match 0.962 >= 0.92
  req_cache_a3  near miss: 2FA, not password    miss           miss     0.771            best similarity 0.771 < threshold 0.92
  req_cache_b1  store                           miss           miss     0.957            entity guard rejected a 0.957 match ([91c8447b] vs [33820b32])
  req_cache_b2  paraphrase of b1                miss           miss     0.941            entity guard rejected a 0.941 match ([91c8447b] vs [33820b32])
  req_no_cache  time-sensitive                  miss           bypass   -                time-sensitive prompt ('current')
  req_no_cache  time-sensitive, repeated        miss           bypass   -                time-sensitive prompt ('current')
  opp_use       store                           miss           miss     0.340            best similarity 0.340 < threshold 0.92
  opp_not_use   opposite: negated               miss           miss     0.989            polarity guard rejected a 0.989 match (negation: only the new prompt i
  opp_enable    store                           miss           miss     0.714            best similarity 0.714 < threshold 0.92
  opp_disable   opposite: disable vs enable     miss           miss     0.905            best similarity 0.905 < threshold 0.92
  opp_adv       store                           miss           miss     0.332            best similarity 0.332 < threshold 0.92
  opp_disadv    opposite: disadvantages         miss           miss     0.937            polarity guard rejected a 0.937 match (opposites: disadvantage vs adva
  opp_on        store                           miss           miss     0.470            best similarity 0.470 < threshold 0.92
  opp_off       opposite: turn off vs turn on   miss           miss     0.952            polarity guard rejected a 0.952 match (opposites: turn off vs turn on)
  opp_raise     store                           miss           miss     0.257            best similarity 0.257 < threshold 0.92
  opp_cut       opposite: reduce vs increase    miss           miss     0.954            polarity guard rejected a 0.954 match (opposites: reduce vs increase)
  ord_c2f       store                           miss           miss     0.270            best similarity 0.270 < threshold 0.92
  ord_f2c       reversed: Fahrenheit to Celsius miss           miss     0.996            order guard rejected a 0.996 match (order: 91c8447b, fahrenheit, celsi

free-tier key (threshold 0.85)
  request       expectation                     x-prism-cache  log      best similarity  note
  req_cache_a1  store                           miss           miss     0.006            best similarity 0.006 < threshold 0.85
  req_cache_a2  paraphrase of a1                hit            hit      0.964            semantic match 0.964 >= 0.85
  req_cache_a3  near miss: 2FA, not password    miss           miss     0.785            best similarity 0.785 < threshold 0.85
  req_cache_b1  store                           miss           miss     0.171            best similarity 0.171 < threshold 0.85
  req_cache_b2  paraphrase of b1                miss           miss     0.719            best similarity 0.719 < threshold 0.85
  req_no_cache  time-sensitive                  miss           bypass   -                time-sensitive prompt ('current')
  req_no_cache  time-sensitive, repeated        miss           bypass   -                time-sensitive prompt ('current')
  opp_use       store                           miss           miss     0.309            best similarity 0.309 < threshold 0.85
  opp_not_use   opposite: negated               miss           miss     0.989            polarity guard rejected a 0.989 match (negation: only the new prompt i
  opp_enable    store                           miss           miss     0.732            best similarity 0.732 < threshold 0.85
  opp_disable   opposite: disable vs enable     miss           miss     0.913            polarity guard rejected a 0.913 match (opposites: disable vs enable)
  opp_adv       store                           miss           miss     0.384            best similarity 0.384 < threshold 0.85
  opp_disadv    opposite: disadvantages         miss           miss     0.943            polarity guard rejected a 0.943 match (opposites: disadvantage vs adva
  opp_on        store                           miss           miss     0.473            best similarity 0.473 < threshold 0.85
  opp_off       opposite: turn off vs turn on   miss           miss     0.953            polarity guard rejected a 0.953 match (opposites: turn off vs turn on)
  opp_raise     store                           miss           miss     0.305            best similarity 0.305 < threshold 0.85
  opp_cut       opposite: reduce vs increase    miss           miss     0.961            polarity guard rejected a 0.961 match (opposites: reduce vs increase)
  ord_c2f       store                           miss           miss     0.319            best similarity 0.319 < threshold 0.85
  ord_f2c       reversed: Fahrenheit to Celsius miss           miss     0.997            order guard rejected a 0.997 match (order: 4de5bf7e, fahrenheit, celsi

RESULT: PASS - no false hits
```

The `entity guard rejected` notes on the search key are matches against the smoke test's earlier entries,
which carry a different id prefix: the guard turning them into misses is what keeps the smoke test green on
every run. (The script waits out the free-tier key's 10 requests a minute twice; those lines are omitted.)

**Adversarial pairs.** Embeddings barely register negation, opposites or word order: "Why should I not use Kafka
for event streaming?" scores about 0.99 against the cached "Why should I use Kafka ...", "turn off dark mode" about
0.95 against "turn on", and "convert Fahrenheit to Celsius" over 0.99 against "Celsius to Fahrenheit". Before the
polarity and order guards (ADR 0008) these were served the opposite answer; now each is rejected, with the reason
in the request log. The six pairs above run on every `verify.sh`.

The guards are rules, and they were tested beyond these six pairs with prompts written after the code (both keys):
reversed MySQL/Postgres and Redis/Memcached, "10 km to miles" reversed, "from s3 to my laptop" reversed, a lower-case
"redis faster than memcached" reversed and "speed up" vs "slow down" were all rejected; genuine paraphrases
("shouldn't" vs "should not", "enable" vs "turn on", "set up SSH keys" asked two ways, "best way to learn Rust" vs
"how should I go about learning Rust") all still hit. Two kinds still got through: an opposite missing from the word
list ("make my emails shorter" vs "longer", 0.93, a hit on the free tier) and a reversal with a two-word name after
"to" ("from s3 to my old laptop" vs the reverse, 0.995). Those need the second-stage verifier described below.

**Pair B.** "What is the refund policy for annual plans?" vs "If I bought an annual plan, can I get my
money back?" is a genuine paraphrase, but its cosine similarity (0.73 without a shared prefix) is
**lower** than the near-miss A3's (0.82), which must not hit. We measured three models:

| | all-MiniLM-L6-v2 | bge-small-en-v1.5 | e5-small-v2 |
|---|---|---|---|
| A1~A2 (should hit) | 0.964 | 0.982 | 0.979 |
| A1~A3 (should miss) | **0.821** | **0.898** | **0.972** |
| B1~B2 (should hit) | **0.732** | **0.887** | **0.915** |

In every model B scores below A3, so no single cosine threshold can hit B without also serving the
password-reset answer to a 2FA question. We chose correctness: a missed paraphrase costs one extra
provider call; a wrong hit returns a wrong answer. The fix, left as future work, is a second-stage
verifier: a cross-encoder trained on duplicate questions, run on candidates above ~0.7.

**Isolation.** Every cache query filters on `tenant_id`; the Testcontainers test sends the same question
with two keys and asserts no cross-tenant hit.

## 4. Routing eval (the `auto` alias)

`python3 scripts/routing_eval.py` (classification only, through `POST /admin/route`):

```
id            expected  actual       score  note
route_001     fast      fast    ok    -3.5  short, easy fact
route_002     fast      fast    ok    -4.0  short, easy conversion
route_003     fast      fast    ok    -3.0  short, easy writing
route_004     fast      fast    ok    -2.2  short, easy definition
route_005     fast      fast    ok    -4.5  short, easy translation
route_006     fast      fast    ok    -3.5  trap: long input, trivial lookup task
route_007     fast      fast    ok   -4.93  trap: long input, trivial extraction task
route_008     fast      fast    ok    -3.5  short, easy extraction
route_009     fast      fast    ok   -2.94  short, easy arithmetic
route_010     fast      fast    ok    -4.0  trap: longer input, trivial rewrite task
route_011     smart     smart   ok    2.98  trap: short but hard proof
route_012     smart     smart   ok    4.45  trap: short but multi-step estimation
route_013     smart     smart   ok     5.0  trap: short but deep systems reasoning
route_014     smart     smart   ok    4.46  complex design task
route_015     smart     smart   ok     5.4  complex comparison and recommendation
route_016     smart     smart   ok    4.98  hard algorithm with explanation
route_017     smart     smart   ok     4.0  deep distributed-systems question
route_018     smart     smart   ok     4.0  complex planning task
route_019     smart     smart   ok     3.0  trap: shortish but expert debugging
route_020     smart     smart   ok    4.06  trap: short but requires a proof

Accuracy:              20/20 = 100%
Length-only baseline:  15/20 = 75%  (best possible word-count cut-off: > 7 words -> smart)
Router latency:        median 10.7 ms, max 25.4 ms per prompt (in-process, no LLM call)
```

The graded set runs first, against a freshly started JVM; the three sets after it show the warm figure, a
median of 4-5 ms per prompt.

The length baseline shown is the *best possible* word-count cut-off on this set, which is stricter than
the brief's ~60% figure.

**Method** (ADR 0011): instruction extraction (pasted context removed), ~25 weighted task-type signals,
and a nearest-neighbour vote over 80 labelled example prompts written independently of the eval set.
No LLM call, so routing adds ~5 ms instead of a second model round trip. Every decision is logged with
its reason and returned in `x-prism-route`.

**Honesty about generalisation.** The signals were designed by someone who had read the eval set, so
100% on it proves little on its own. We therefore also wrote held-out sets:

| Set | Accuracy | Length-only |
|---|---|---|
| `routing_heldout.jsonl` (30, first blind run) | 87% (26/30) | 83% |
| same set after fixing the 4 failure classes it exposed | 97% (29/30), no longer blind | 83% |
| `routing_heldout_v2.jsonl` (20, written after the fixes, never tuned on) | **95% (19/20)** | 75% |
| `routing_heldout_external.jsonl` (32, written in an external review, with keyword traps) | 88% (28/32) blind; **91% (29/32)** after one rule fix | 72% |

Failure classes found by the first held-out run, each fixed as a general rule: "debug" as a log level
read as debugging, "find the bug" read as extraction, "which file and line …?" not seen as extraction,
and "what does X do?" not seen as a definition. The remaining misses (for example "How many deploys did the api
service have?") have no signal either way, and go to the strong tier by policy: unsure means quality first.

The external set's blind run exposed one more class: "how would you **find the** 100 most frequent IP
addresses?" read as extraction and went to the cheap tier. "find the" followed by a superlative, a count or
an order statistic now counts as a computation (29/32). Its three remaining misses all go to the strong tier,
the cheaper mistake: two easy prompts with no keyword signal, where the neighbour vote leaned complex (j07) or
was too close to call (j11), and "time complexity of a hash-map lookup" (j13), where the word "complexity" wins. The same review questioned the neighbour vote, which ignores how similar
the neighbours are; two similarity-aware versions calibrated from the exemplar bank were measured and were
worse on every held-out set, so the vote is unchanged (ADR 0011).

## 5. Streaming

Through nginx (`proxy_buffering off`), a mock stream arrives as 24 events spread over ~510 ms: first at
35 ms, last at 548 ms. The mock paces ~20 ms per token, so nothing is buffered. Streams end with
`: prism-cost-usd=…` (an SSE comment) and `data: [DONE]`; usage and cost are in the request log.

Mid-stream failure, idle timeout and keep-alive comments are covered by `OpenAiCompatibleAdapterTest`
against an in-process fake provider (the provided mock cannot fail mid-stream): the client receives an
error event with `finish_reason: "error"` and `[DONE]`, never another provider's continuation.

## 6. Failure drill

`python3 scripts/failure_drill.py` injects failures into mock `alpha` live:

```
[1] alpha healthy
  PASS  served by the primary  (200 alpha/alpha-small fallback=false)

[2] alpha down (every request -> 503)
  PASS  failover to beta, caller sees 200  (200 beta/beta-small fallback=true in 20 ms)

[3] alpha slow (3000 ms before responding)
  PASS  timeout fires and beta answers well before alpha would  (200 beta/beta-small in 2.09 s)
  PASS  health probes route around the slow provider  (200 beta/beta-small in 43 ms)

[4] alpha flaky (30% of requests -> 500)
  PASS  retries absorb transient errors  (20/20 succeeded, 20 on alpha)

[5] alpha restored
  PASS  traffic returns to the primary  (200 alpha/alpha-small)

6/6 checks passed
```

Measured separately on the same stack: with alpha marked unhealthy, traffic was back on alpha **0.56 s** after
the restore. Active probes run every second (it was 3 s, which meant up to 3 s on the fallback after a restore).

The request log records every attempt, e.g. a flaky request:
`[{"provider":"alpha","attempt":1,"outcome":"http_500"},{"provider":"alpha","attempt":2,"outcome":"ok"}]`.

## 7. Gateway added latency

Measured from the request log (`latency_ms - upstream_ms`, where `upstream_ms` is the time inside the
provider calls including retries), over 60 sequential requests in Docker: **p50 2 ms, p95 12 ms**,
with a total p50 of 4 ms against the instant mock. Every response also carries a `Server-Timing` header,
e.g. `auth;dur=0.0, ratelimit;dur=1.9, budget;dur=1.9, upstream;dur=1.6, total;dur=5.8`.

Under a concurrent burst on a single key, the budget stage grows (tens of ms): admissions for one key
serialise on that key's usage row, which is exactly what makes them race-free. Keys do not contend with each other.

## Bugs found by verification (and fixed)

1. **Streams would have been cut after 2 s.** The JDK HttpClient's request timeout runs until the whole
   response has arrived when the body is consumed line by line. Found by `aStreamThatGoesSilentIsCutByTheIdleTimeout`;
   streams now use the gateway's own first-byte and idle timeouts (ADR 0010).
2. **False "budget exhausted" on the first requests of a month.** Concurrent first requests raced to create
   the month's usage row; the loser saw "row exists", skipped its retry and rejected. Found by the load test
   on a fresh database (2 of 30 requests got 402 with $0 spent); fixed and covered by
   `firstRequestsOfAMonthNeverFalselyRejectEachOther`.
3. **Flaky providers looked healthy.** One success cleared the failure history even when the provider
   failed most requests. Found by `ProviderHealthTest`; history is now cleared only after an outage (a failure streak).

## Found by an external review (and fixed)

4. **Opposite questions shared an answer.** "not use Kafka" (0.989), "disadvantages" (0.935) and "disable 2FA"
   (0.909) were served the cached answer to the opposite question. Fixed by the polarity guard; the pairs are now
   part of `cache_pairs.py` (section 3).
5. **A client that left during a cached stream replay left no log row** and kept its budget reservation: the
   `IOException` escaped before the log was written. The log writer now writes once per request and the pipeline
   calls it from a `finally` block; `ChatPipelineClientGoneTest` fails on the old code.
6. **An expired cache entry blocked re-caching** until the 5-minute purge (`ON CONFLICT DO NOTHING` hit the
   expired row). The insert now replaces an expired entry and keeps a live one.
7. **No end-to-end test of the pipeline in the build.** Added `ChatPipelineIntegrationTest` (section 2), which
   also covers allowlist enforcement for direct aliases and for `auto`.
8. **Reversed and unlisted opposite questions still shared an answer** (found by the re-grade after fix 4):
   "Fahrenheit to Celsius" got the "Celsius to Fahrenheit" answer (0.995), and "turn off" and "reduce" were not on
   the word list (0.952, 0.962). Fixed with the order guard and with opposites as synonym groups that include
   phrases; three of these pairs joined `cache_pairs.py`. Two narrower kinds remain (section 3, known limitations).

## Known limitations

- Rate limiting depends on Redis; if Redis is down the limiter fails open (budgets still hold, in Postgres).
- A gateway crash mid-request leaves that request's budget reservation in place until cleared.
- Pair B needs a second-stage verifier (section 3).
- The polarity and order guards are rules: an opposite missing from the word list ("shorter" vs "longer") or a
  reversal with a two-word name after "to" still relies on the similarity threshold alone. The same second-stage
  verifier is the general fix.
- Health state is per gateway instance (each probes on its own, like Envoy); the control plane merges views.
- Provider concurrency is not capped per provider (a bulkhead would smooth bursts against fragile providers such as the mock).
