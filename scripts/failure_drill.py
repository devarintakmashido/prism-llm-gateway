#!/usr/bin/env python3
"""Automated failure drill against the provided mock providers (the same steps as the demo).

  1. alpha healthy        -> served by alpha, no fallback
  2. alpha down (503)     -> served by beta, x-prism-fallback: true, no error for the caller
  3. alpha slow (3000 ms) -> first-byte timeout fires (~2 s), served by beta; never hangs
  4. alpha flaky (30%)    -> retries absorb the errors; every request succeeds
  5. alpha restored       -> traffic returns to alpha within one health-probe interval (1 s)

    python3 scripts/failure_drill.py [--url http://localhost:8080] [--alpha http://localhost:9001]
"""

import argparse
import json
import sys
import time
import urllib.request
import uuid


def chat(base, key, prompt):
    body = json.dumps({"model": "fast", "messages": [{"role": "user", "content": prompt}]}).encode()
    req = urllib.request.Request(base + "/v1/chat/completions", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            resp.read()
            h = {k.lower(): v for k, v in resp.headers.items()}
            return resp.status, h.get("x-prism-provider"), h.get("x-prism-fallback"), time.monotonic() - start
    except urllib.error.HTTPError as e:
        return e.code, None, None, time.monotonic() - start


def configure(alpha, cfg):
    req = urllib.request.Request(alpha + "/admin/config", data=json.dumps(cfg).encode(), method="POST")
    urllib.request.urlopen(req, timeout=5).read()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:8080")
    p.add_argument("--alpha", default="http://localhost:9001")
    p.add_argument("--key", default="prism-sk-research-4d5e6f")
    args = p.parse_args()
    ask = lambda label: chat(args.url, args.key, f"drill {label} {uuid.uuid4().hex[:6]}")
    results = []

    def check(name, ok, detail):
        results.append(ok)
        print(f"  {'PASS' if ok else 'FAIL'}  {name}  ({detail})")

    try:
        print("\n[1] alpha healthy")
        configure(args.alpha, {"mode": "ok", "latency_ms": 0, "fail_rate": 0})
        time.sleep(4)
        s, prov, fb, t = ask("healthy")
        check("served by the primary", s == 200 and prov == "alpha/alpha-small" and fb == "false", f"{s} {prov} fallback={fb}")

        print("\n[2] alpha down (every request -> 503)")
        configure(args.alpha, {"mode": "down"})
        s, prov, fb, t = ask("down")
        check("failover to beta, caller sees 200", s == 200 and prov == "beta/beta-small" and fb == "true",
              f"{s} {prov} fallback={fb} in {t * 1000:.0f} ms")

        print("\n[3] alpha slow (3000 ms before responding)")
        configure(args.alpha, {"mode": "ok", "latency_ms": 0})
        time.sleep(4)  # let a probe mark alpha healthy again
        configure(args.alpha, {"latency_ms": 3000})
        s, prov, fb, t = ask("slow")
        check("timeout fires and beta answers well before alpha would", s == 200 and prov == "beta/beta-small" and t < 2.9,
              f"{s} {prov} in {t:.2f} s")
        time.sleep(7)
        s, prov, fb, t = ask("slow-after-probes")
        check("health probes route around the slow provider", s == 200 and prov == "beta/beta-small" and t < 0.5,
              f"{s} {prov} in {t * 1000:.0f} ms")

        print("\n[4] alpha flaky (30% of requests -> 500)")
        configure(args.alpha, {"mode": "ok", "latency_ms": 0, "fail_rate": 0})
        time.sleep(4)
        configure(args.alpha, {"fail_rate": 0.3})
        outcomes = [ask(f"flaky-{i}") for i in range(20)]
        ok = sum(1 for o in outcomes if o[0] == 200)
        on_alpha = sum(1 for o in outcomes if o[1] == "alpha/alpha-small")
        check("retries absorb transient errors", ok == 20, f"{ok}/20 succeeded, {on_alpha} on alpha")

        print("\n[5] alpha restored")
        configure(args.alpha, {"mode": "ok", "latency_ms": 0, "fail_rate": 0})
        time.sleep(4)
        s, prov, fb, t = ask("restored")
        check("traffic returns to the primary", s == 200 and prov == "alpha/alpha-small" and fb == "false", f"{s} {prov}")
    finally:
        configure(args.alpha, {"mode": "ok", "latency_ms": 0, "fail_rate": 0})

    print(f"\n{sum(results)}/{len(results)} checks passed")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
