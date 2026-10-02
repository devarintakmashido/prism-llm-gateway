#!/usr/bin/env python3
"""Shows that the rate limit is shared by every gateway replica, because its state lives in Redis.

Start two replicas first (nginx resolves the replicas when it starts, so restart the edge):

    docker compose up -d --scale gateway=2 && docker compose restart edge
    python3 scripts/two_replicas_demo.py

Fires 30 simultaneous requests at the free-tier key (10 requests per minute) through the edge. The
edge spreads them over both replicas; the limit must still admit exactly 10 in total, and both
replicas must have handled some of them. With per-instance counters each replica would admit 10 (20 total).
Which replica wins the race for the ten slots is arbitrary (a freshly started replica is slower), so the
checks are on the total admitted and on both replicas having received traffic.
"""

import argparse
import collections
import concurrent.futures
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

KEY = "prism-sk-free-7g8h9i"
WARMUP_KEY = "prism-sk-research-4d5e6f"  # a different key, so the free-tier window stays empty


def get_json(url, token):
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())


def chat(base, prompt, key=KEY):
    req = urllib.request.Request(base + "/v1/chat/completions", method="POST",
                                 data=json.dumps({"model": "fast", "messages": [{"role": "user", "content": prompt}]}).encode(),
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            resp.read()
            return resp.status, resp.headers.get("x-request-id")
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("x-request-id")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", default="http://localhost:8080")
    p.add_argument("--admin-token", default="prism-admin-dev-token")
    p.add_argument("--requests", type=int, default=30)
    args = p.parse_args()

    time.sleep(3)  # let each replica publish its health view
    fleet = get_json(args.url + "/admin/providers/health", args.admin_token)["gateway_instances"]
    print(f"gateway replicas reporting: {fleet}")
    if len(fleet) < 2:
        print("Need 2 replicas: docker compose up -d --scale gateway=2 && docker compose restart edge")
        return 2

    print("Warming both replicas (JIT, connection pools), then waiting 61 s so the key's window is empty ...", flush=True)
    for i in range(12):
        chat(args.url, f"warm-up {uuid.uuid4().hex} what is a semaphore?", WARMUP_KEY)
    time.sleep(61)

    salt = uuid.uuid4().hex[:8]
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.requests) as pool:
        results = list(pool.map(lambda i: chat(args.url, f"({salt}) Question number {i}: what is a mutex?"),
                                range(args.requests)))
    admitted_ids = {rid for status, rid in results if status == 200}
    all_ids = {rid for _, rid in results}
    limited = sum(1 for status, _ in results if status == 429)
    print(f"{len(results)} simultaneous requests: {len(admitted_ids)} admitted (200), {limited} rate limited (429)")

    logs = get_json(f"{args.url}/admin/logs?team=free-tier&limit=200", args.admin_token)
    handled = collections.Counter(row["gateway_instance"] for row in logs if row["request_id"] in all_ids)
    admitted = collections.Counter(row["gateway_instance"] for row in logs if row["request_id"] in admitted_ids)
    for instance in sorted(handled):
        print(f"  replica {instance}: handled {handled[instance]}, admitted {admitted[instance]}")

    limit = 10
    ok = len(admitted_ids) == limit and len(handled) >= 2
    print("PASS: one shared limit across replicas" if ok else
          f"FAIL: expected exactly {limit} admitted while both replicas received traffic")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
