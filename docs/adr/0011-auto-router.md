# ADR 0011: The auto router: task signals + nearest neighbours, no LLM call

**Status:** accepted

## Context
`"model": "auto"` must pick fast or smart per prompt. An LLM judge would add a whole model round trip
before the real one, roughly doubling latency for simple prompts (the instructor's main concern).
Length is a trap: the eval set contains short-but-hard and long-but-trivial prompts.

## Decision
A classifier that runs in-process in ~5 ms:
1. **Instruction extraction**: quoted material and code fences are removed; for long prompts only the
   framing sentence and the final ask are kept. "Here is our 14-day roster ... Who is on call Thursday?"
   is classified as "Here is our on-call roster … Who is on call this Thursday?".
2. **Task signals**: about 25 task types with weights, e.g. proof +3, debugging +2, design +2,
   translation -3, rewrite -2.5, lookup -2. This follows the idea behind OpenRouter's Auto Router, which
   classifies prompts into ~30 task types rather than asking an LLM.
3. **Nearest neighbours**: similarity-weighted vote of the 5 nearest of 80 labelled example prompts
   (`router/exemplars.jsonl`, written independently of the eval set), using the shared MiniLM embeddings.
4. score = signals + 1.5 x vote. |score| < 0.5 counts as uncertain and goes to the **strong** tier:
   an easy prompt on a strong model costs a little money, a hard prompt on a weak model costs a bad answer.
5. Decisions are cached by prompt hash.

Every decision is logged with its reason (signals, score, nearest examples) and returned in the
`x-prism-route` header.

## Results
| set | router | best length-only cut-off |
|---|---|---|
| data/routing_eval.jsonl (graded, 20) | 100% | 75% |
| data/routing_heldout.jsonl (v1, 30; its misses drove 4 rule fixes) | 97% (87% before the fixes) | 83% |
| data/routing_heldout_v2.jsonl (fresh, 20, never tuned on) | 95% | 75% |
| data/routing_heldout_external.jsonl (32, written in an external review, with keyword traps) | 91% (88% on its blind first run) | 72% |

The v1 misses showed real failure classes: "debug" as a log level, "find the bug" read as extraction,
"which file and line ...?" extraction, and "what does X do?" definitions. The remaining misses are prompts
with no signal either way, which go to the strong tier by policy.

The external set's first run missed "how would you find the 100 most frequent IP addresses?": "find
the" read as extraction. "find the" followed by a superlative, a count or an order statistic asks to
compute something, so it no longer counts as extraction (one rule fix, 28/32 -> 29/32).

## Considered and rejected: a similarity-aware neighbour vote
The vote is normalised (`weighted / total`), so five neighbours at 0.3 similarity vote as strongly
as five at 0.95. A review called that a flaw, so we measured it. In MiniLM space, two exemplars of
the *same* task type have a median similarity of only 0.13, and *different* task types reach 0.22 at
the 95th percentile and 0.34 at the 99th: a top neighbour at 0.3-0.4 is already informative. Two
variants calibrated from the bank itself (neighbours below the p95 floor ignored, trust scaled up to
the p99 level; or each neighbour weighted by its similarity above the floor) were both worse:

| vote | graded | heldout v1 | heldout v2 | external |
|---|---|---|---|---|
| normalised (kept) | 20/20 | 29/30 | 19/20 | 29/32 |
| floor + confidence scaling | 20/20 | 29/30 | 18/20 | 28/32 |
| additive above the floor | 20/20 | 28/30 | 17/20 | 26/32 |

They mostly pushed signal-less easy prompts into the uncertain band (and so to the strong tier).
The normalised vote stays; its weakness is the bank's size, which the next step addresses.

## Next step (with an API key)
Label a few hundred prompts offline with a strong LLM, and use them as the exemplar bank or to fit the
weights. That gives LLM-quality judgement at nearest-neighbour speed (the RouteLLM / NotDiamond approach).
