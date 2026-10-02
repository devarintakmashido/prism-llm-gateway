#!/usr/bin/env python3
"""Runs the provided load test and reconciles its client-side totals with Prism's usage API.

The load test prints what the client saw (accepted requests, tokens from response bodies, sum of
x-prism-cost-usd headers). This script records the time window of the burst, asks the usage API
for the same key and window, and checks that both sides agree exactly.

    python3 scripts/reconcile.py                       # free-tier key, 30 requests, concurrency 10
    python3 scripts/reconcile.py --wait-for-window     # first wait 61s so earlier traffic can't eat the RPM window
"""

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
import time
import urllib.request


def usage(base, admin_token, key, start, end):
    url = (f"{base}/admin/usage?key={urllib.request.quote(key)}"
           f"&from={urllib.request.quote(start)}&to={urllib.request.quote(end)}")
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {admin_token}"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:8080")
    p.add_argument("--admin-token", default="prism-admin-dev-token")
    p.add_argument("--key", default="prism-sk-free-7g8h9i")
    p.add_argument("--requests", type=int, default=30)
    p.add_argument("--concurrency", type=int, default=10)
    p.add_argument("--rpm-limit", type=int, default=10)
    p.add_argument("--wait-for-window", action="store_true", help="sleep 61s first so the RPM window is empty")
    args = p.parse_args()

    if args.wait_for_window:
        print("Waiting 61s so the key's one-minute rate-limit window is empty ...", flush=True)
        time.sleep(61)

    start = dt.datetime.now(dt.timezone.utc).isoformat()
    result = subprocess.run([sys.executable, "scripts/load_test.py", "--url", args.url, "--key", args.key,
                             "--model", "fast", "--requests", str(args.requests),
                             "--concurrency", str(args.concurrency), "--rpm-limit", str(args.rpm_limit)],
                            capture_output=True, text=True)
    time.sleep(0.5)
    end = dt.datetime.now(dt.timezone.utc).isoformat()
    print(result.stdout)

    def grab(label):
        m = re.search(rf"{label}:\s+([0-9.]+)", result.stdout)
        return float(m.group(1)) if m else None

    client = {
        "accepted": int(grab("accepted requests") or 0),
        "prompt_tokens": int(grab("prompt tokens") or 0),
        "completion_tokens": int(grab("completion tokens") or 0),
        "cost_usd": grab("sum of cost headers") or 0.0,
    }
    server = usage(args.url, args.admin_token, args.key, start, end)
    served = {
        "accepted": server["served"],
        "prompt_tokens": server["prompt_tokens"],
        "completion_tokens": server["completion_tokens"],
        "cost_usd": float(server["cost_usd"]),
    }

    print(f"Reconciliation for {server['team']} between {start} and {end}")
    print(f"  {'':<20}{'client (load test)':>22}{'usage API':>16}   match")
    ok = True
    for field in ("accepted", "prompt_tokens", "completion_tokens", "cost_usd"):
        c, s = client[field], served[field]
        # The load test prints the cost sum with 6 decimals; our headers carry 9, so compare at 6.
        same = abs(c - s) < 5e-7 if field == "cost_usd" else c == s
        ok &= same
        fmt = (lambda v: f"{v:.9f}") if field == "cost_usd" else (lambda v: str(v))
        print(f"  {field:<20}{fmt(c):>22}{fmt(s):>16}   {'yes' if same else 'NO'}")
    print(f"  rejected by rate limiter (usage API): {server['rejected']}")
    over = client["accepted"] - args.rpm_limit
    print(f"\nOver-admission: {max(0, over)} (accepted {client['accepted']} with limit {args.rpm_limit})")
    print("RESULT:", "PASS - totals reconcile exactly" if ok and over <= 0 else "FAIL")
    sys.exit(0 if ok and over <= 0 else 1)


if __name__ == "__main__":
    main()
