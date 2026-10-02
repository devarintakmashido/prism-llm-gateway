#!/usr/bin/env python3
"""One-command routing eval for Prism's `auto` router. Zero dependencies (Python 3.9+ stdlib).

Sends every prompt in a labelled set through the gateway's router (classification only, no upstream
call) and reports accuracy with per-case expected vs actual and the reason behind each decision.
It also scores a length-only baseline on the same cases, so the gap is visible.

    python3 scripts/routing_eval.py                                  # data/routing_eval.jsonl (the graded set)
    python3 scripts/routing_eval.py --cases data/routing_heldout.jsonl
    python3 scripts/routing_eval.py --json reports/routing_eval.json # also write the JSON report

The labels (`expected_tier`) are only used here, to score. The router never sees them.
"""

import argparse
import json
import statistics
import sys
import urllib.error
import urllib.request


def classify(base, token, prompt):
    req = urllib.request.Request(
        base.rstrip("/") + "/admin/route",
        data=json.dumps({"prompt": prompt}).encode(),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def length_baseline(prompt, threshold_words):
    return "smart" if len(prompt.split()) > threshold_words else "fast"


def best_length_threshold(cases):
    """The most generous length-only baseline: the word-count cut-off that scores best on these cases."""
    best = (0, 0)
    for t in range(1, 80):
        correct = sum(length_baseline(c["prompt"], t) == c["expected_tier"] for c in cases)
        best = max(best, (correct, -t))
    return -best[1], best[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080", help="gateway base URL")
    parser.add_argument("--admin-token", default="prism-admin-dev-token")
    parser.add_argument("--cases", default="data/routing_eval.jsonl")
    parser.add_argument("--json", help="write the full JSON report to this file")
    args = parser.parse_args()

    with open(args.cases, encoding="utf-8") as f:
        cases = [json.loads(line) for line in f if line.strip()]

    rows, micros = [], []
    for case in cases:
        try:
            decision = classify(args.url, args.admin_token, case["prompt"])
        except urllib.error.URLError as e:
            print(f"cannot reach the router at {args.url}/admin/route: {e}", file=sys.stderr)
            sys.exit(2)
        micros.append(decision["micros"])
        rows.append({
            "id": case["id"],
            "expected": case["expected_tier"],
            "actual": decision["tier"],
            "correct": decision["tier"] == case["expected_tier"],
            "score": round(decision["score"], 2),
            "note": case.get("note", ""),
            "reason": decision["reason"],
        })

    correct = sum(r["correct"] for r in rows)
    threshold, baseline_correct = best_length_threshold(cases)

    print(f"\nRouting eval over {args.cases}  ({len(cases)} cases)\n")
    print(f"{'id':<14}{'expected':<10}{'actual':<8}{'':<3}{'score':>7}  note")
    for r in rows:
        mark = "ok" if r["correct"] else "XX"
        print(f"{r['id']:<14}{r['expected']:<10}{r['actual']:<8}{mark:<3}{r['score']:>7}  {r['note']}")
    misses = [r for r in rows if not r["correct"]]
    if misses:
        print("\nMisses (with the router's reasoning):")
        for r in misses:
            print(f"  {r['id']}: expected {r['expected']}, got {r['actual']}\n     {r['reason']}")

    print(f"\nAccuracy:              {correct}/{len(cases)} = {correct / len(cases):.0%}")
    print(f"Length-only baseline:  {baseline_correct}/{len(cases)} = {baseline_correct / len(cases):.0%}"
          f"  (best possible word-count cut-off: > {threshold} words -> smart)")
    print(f"Router latency:        median {statistics.median(micros) / 1000:.1f} ms, "
          f"max {max(micros) / 1000:.1f} ms per prompt (in-process, no LLM call)")

    report = {
        "cases_file": args.cases,
        "total": len(cases),
        "correct": correct,
        "accuracy": round(correct / len(cases), 4),
        "length_baseline": {"threshold_words": threshold, "correct": baseline_correct,
                            "accuracy": round(baseline_correct / len(cases), 4)},
        "latency_ms": {"median": statistics.median(micros) / 1000, "max": max(micros) / 1000},
        "cases": rows,
    }
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
        print(f"\nJSON report written to {args.json}")


if __name__ == "__main__":
    main()
