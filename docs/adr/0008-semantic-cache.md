# ADR 0008: Semantic cache scoping, eligibility, and the entity, polarity and order guards

**Status:** accepted

## Scope (what may share an answer)
- **Tenant**: always. Every lookup filters on `tenant_id`; a cross-tenant hit would be a data leak.
- **Tier**: an answer produced by `fast` never serves `smart`.
- **Context**: the system prompt and earlier turns are hashed into the scope, so a follow-up question
  only matches when the whole conversation before it is identical (`req_multiturn`).
- **Parameters** that change the answer (temperature, max_tokens, response_format, ...) are also in
  the scope: a `max_tokens: 5` request is never served a full-length cached answer.

## Matching
1. Exact match on the normalised prompt hash (free: no embedding needed).
2. Nearest neighbours by cosine similarity (pgvector) at or above the key's threshold (seed: 0.92 search, 0.85 free-tier).
3. **Entity guard**: numbers, ids and capitalised names in either prompt must appear in the other.
4. **Polarity guard**: both prompts must be negated the same way (not, never, without, n't ...), and
   must not sit on opposite sides of a group of opposites. A side lists synonyms and two-word phrases,
   e.g. {enable, activate, turn on, switch on} vs {disable, deactivate, turn off, switch off}, or
   {increase, raise, grow, scale up} vs {decrease, reduce, lower, shrink, scale down}; inflections such
   as "disabled" or "turning" count. A group only one prompt touches is no conflict, and neither is
   the same side in other words: "enable dark mode" still matches "switch on dark mode".
5. **Order guard**: the entities both prompts share must appear in the same order, and no word pair
   around "to", "into" or "than" may be reversed ("kilograms to pounds" vs "pounds to kilograms").
   Filler words are skipped ("from s3 to my laptop" reads as "s3 to laptop"), and for "than" the
   subject before the adjective is compared ("redis [faster] than memcached").

All guards only turn hits into misses, so at worst they cost a saving. The accepted cost of the order
guard: a symmetric question asked the other way round ("difference between Redis and Memcached" vs
"... Memcached and Redis") also misses.

Why the guard: measured similarities that would be wrong hits by cosine alone:
- the same question with a different id prefix, `(3fa9c2b1) What is a load balancer?` vs `(7d0e4a19) ...`: **0.94**.
  Without the guard, the second run of the provided smoke test fails "first request is a miss",
  and the load test's cache-busting stops working.
- a roster question with one name changed (Diego vs Lukas): **0.978**
- `What is 2+3?` vs `What is 2+4?`: 0.82

Why the polarity guard: embeddings barely register negation or opposites. Found in review, after
the entity guard was in place:
- `Why should I use Kafka for event streaming?` vs `Why should I not use Kafka ...`: **0.986**
- `advantages of microservices` vs `disadvantages of microservices`: **0.929** (above the strictest seeded threshold)
- `How do I enable two-factor authentication ...` vs `How do I disable ...`: **0.900** (above free-tier's 0.85)

A re-grade then found two more classes the first word list missed, on both keys:
- reversed order: `How do I convert Celsius to Fahrenheit?` vs `... Fahrenheit to Celsius?`: **0.995**;
  `migrate from MySQL to Postgres` vs `from Postgres to MySQL`: 0.989; `Is Redis faster than Memcached?`
  vs the reverse: 0.994. Embeddings are close to a bag of words here: they barely register order.
- opposites not on the list: `turn on` vs `turn off` dark mode: **0.952**; `increase` vs `reduce` the
  request timeout: 0.962. Hence synonym groups and phrases instead of single pairs.

Six of these pairs are now part of `scripts/cache_pairs.py`, so `verify.sh` fails if any of them hits again.
The guards are rules. Measured after the last fix, two kinds still pass: an opposite missing from the
list ("make my emails shorter" vs "longer", 0.93) and a reversal the word-pair rule cannot see ("from s3
to my old laptop" vs "from my old laptop to s3", 0.995). The general fix is the cross-encoder verifier
described below.

## What is never cached
Errors, truncated answers (`finish_reason != stop`), refusals, tool calls, `n > 1`, requests with
`Cache-Control: no-cache`, and time-sensitive prompts ("current", "today", "status of", ...), such as
the provided `req_no_cache`. Prompts longer than 150 words are exact-match only: a small change in a
long context changes the answer but barely moves the embedding (and MiniLM truncates at 256 tokens).

## Known limitation: pair B
`What is the refund policy for annual plans?` vs `If I bought an annual plan, can I get my money back?`
scores 0.73, **below** the near-miss A3 (0.82), in every model we tried. No single cosine threshold
can hit B and miss A3. The smoke test reports B as a WARN, which the brief allows. The fix is a
second-stage verifier (a cross-encoder trained on duplicate questions) over candidates above ~0.7.

## Eviction
24 h TTL, expired rows purged every 5 minutes, and at most 5000 entries per tenant (least recently used first).
