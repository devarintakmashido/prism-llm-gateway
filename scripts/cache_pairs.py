#!/usr/bin/env python3
"""Reports the semantic cache's behaviour on the provided sample pairs (data/sample_requests.jsonl):
pair A (req_cache_a1/a2), the near miss (req_cache_a3), pair B (req_cache_b1/b2), and the
time-sensitive req_no_cache, for each cache-enabled seed key (search: threshold 0.92, free-tier: 0.85).
It then sends six adversarial pairs that embed above both thresholds: negation, enable/disable,
advantages/disadvantages, turn on/turn off, increase/reduce, and a reversed conversion (Celsius to
Fahrenheit). Serving the cached answer to the second question of a pair would be a wrong answer.

Each run prefixes the prompts with a fresh id so earlier runs cannot interfere, exactly like the
smoke test does. The similarity of the best candidate comes from the request log.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid


def chat(base, key, prompt, max_waits=3):
    """One chat call. A 429 is the gateway doing its job (the free-tier key allows 10 requests a minute,
    and earlier steps of verify.sh may have used the window), so wait for the advertised Retry-After."""
    for attempt in range(max_waits + 1):
        req = urllib.request.Request(base + "/v1/chat/completions", method="POST",
                                     data=json.dumps({"model": "fast", "messages": [{"role": "user", "content": prompt}]}).encode(),
                                     headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                resp.read()
                return {k.lower(): v for k, v in resp.headers.items()}
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == max_waits:
                raise
            wait = min(int(e.headers.get("Retry-After", "5")) + 1, 65)
            print(f"  (429 rate limited; waiting {wait}s per Retry-After)", flush=True)
            time.sleep(wait)


def last_log(base, admin, team):
    req = urllib.request.Request(f"{base}/admin/logs?team={team}&limit=1", headers={"Authorization": f"Bearer {admin}"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())[0]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:8080")
    p.add_argument("--admin-token", default="prism-admin-dev-token")
    args = p.parse_args()
    samples = {}
    with open("data/sample_requests.jsonl", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                row = json.loads(line)
                if row["body"]["messages"]:
                    samples[row["id"]] = row["body"]["messages"][-1]["content"]
    # Added for verification (not in the provided pack): opposites that cosine similarity alone would match.
    samples.update({
        "opp_use": "Why should I use Kafka for event streaming?",
        "opp_not_use": "Why should I not use Kafka for event streaming?",
        "opp_enable": "How do I enable two-factor authentication on my account?",
        "opp_disable": "How do I disable two-factor authentication on my account?",
        "opp_adv": "What are the advantages of microservices?",
        "opp_disadv": "What are the disadvantages of microservices?",
        "opp_on": "How do I turn on dark mode in the dashboard?",
        "opp_off": "How do I turn off dark mode in the dashboard?",
        "opp_raise": "How do I increase the request timeout?",
        "opp_cut": "How do I reduce the request timeout?",
        "ord_c2f": "How do I convert Celsius to Fahrenheit?",
        "ord_f2c": "How do I convert Fahrenheit to Celsius?",
    })

    steps = [("req_cache_a1", "store", None), ("req_cache_a2", "paraphrase of a1", "hit"),
             ("req_cache_a3", "near miss: 2FA, not password", "miss"), ("req_cache_b1", "store", None),
             ("req_cache_b2", "paraphrase of b1", "hit"), ("req_no_cache", "time-sensitive", "bypass"),
             ("req_no_cache", "time-sensitive, repeated", "bypass"),
             ("opp_use", "store", None), ("opp_not_use", "opposite: negated", "miss"),
             ("opp_enable", "store", None), ("opp_disable", "opposite: disable vs enable", "miss"),
             ("opp_adv", "store", None), ("opp_disadv", "opposite: disadvantages", "miss"),
             ("opp_on", "store", None), ("opp_off", "opposite: turn off vs turn on", "miss"),
             ("opp_raise", "store", None), ("opp_cut", "opposite: reduce vs increase", "miss"),
             ("ord_c2f", "store", None), ("ord_f2c", "reversed: Fahrenheit to Celsius", "miss")]
    ok = True
    for team, key, threshold in (("search", "prism-sk-search-1a2b3c", 0.92), ("free-tier", "prism-sk-free-7g8h9i", 0.85)):
        salt = uuid.uuid4().hex[:8]
        print(f"\n{team} key (threshold {threshold})")
        print(f"  {'request':<14}{'expectation':<32}{'x-prism-cache':<15}{'log':<9}{'best similarity':<17}note")
        for sample_id, label, expected in steps:
            headers = chat(args.url, key, f"({salt}) {samples[sample_id]}")
            log = last_log(args.url, args.admin_token, team)
            sim = log.get("cache_similarity")
            sim_text = f"{sim:.3f}" if isinstance(sim, (int, float)) else "-"
            print(f"  {sample_id:<14}{label:<32}{headers.get('x-prism-cache'):<15}{log['cache']:<9}{sim_text:<17}"
                  f"{(log.get('cache_note') or '')[:70]}")
            if expected in ("miss", "bypass") and log["cache"] == "hit":
                ok = False  # a wrong hit is a correctness failure; a missed paraphrase is only a lost saving
    print("\nRESULT:", "PASS - no false hits" if ok else "FAIL - a request was served a wrong cached answer")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
