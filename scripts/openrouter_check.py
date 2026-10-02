#!/usr/bin/env python3
"""Live check of the optional OpenRouter provider. Zero dependencies (Python 3.9+ stdlib).

OpenRouter is the LAST fallback in the `fast` and `smart` chains, so it only serves traffic when alpha and beta
have both failed. This script takes both mock providers down, sends real requests through the gateway and checks
that OpenRouter answered them (non-streaming and streaming, both aliases), with a cost computed from the
provider's own usage object. It always puts the mocks back, even if a check fails.

    python3 scripts/openrouter_check.py            # needs OPENROUTER_API_KEY in .env and the stack running

Uses real, billed (cheap) calls: about six requests of a few tokens each.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

failures = 0


def check(ok, label, detail=""):
    global failures
    failures += 0 if ok else 1
    print(f"  {'PASS' if ok else 'FAIL'}  {label}" + (f"  ({detail})" if detail else ""))


def mock_mode(port, mode):
    req = urllib.request.Request(f"http://localhost:{port}/admin/config", data=json.dumps({"mode": mode}).encode(),
                                 method="POST")
    urllib.request.urlopen(req, timeout=5).read()


def chat(base, key, model, prompt, stream=False):
    body = json.dumps({"model": model, "stream": stream, "max_tokens": 40,
                       "messages": [{"role": "user", "content": prompt}]}).encode()
    req = urllib.request.Request(base + "/v1/chat/completions", data=body, method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": f"Bearer {key}"})
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read().decode()
            return resp.status, dict(resp.headers), raw, time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode(), time.time() - t0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--key", default="prism-sk-research-4d5e6f", help="a virtual key that may use fast and smart")
    args = parser.parse_args()

    try:
        with urllib.request.urlopen(args.url + "/health", timeout=5) as r:
            providers = json.load(r).get("providers", {})
    except OSError as e:
        print(f"Gateway not reachable at {args.url} ({e}). Start it with `docker compose up -d`.")
        sys.exit(2)
    if "openrouter" not in providers:
        print("OpenRouter is not enabled: set OPENROUTER_API_KEY in .env and run `docker compose up -d`.")
        sys.exit(2)
    print(f"Providers: {providers}")

    stamp = int(time.time())  # unique prompts, so the semantic cache cannot answer instead
    try:
        print("\n[1] alpha and beta down")
        mock_mode(9001, "down")
        mock_mode(9002, "down")

        for alias, expect in (("fast", "openrouter/openrouter-small"), ("smart", "openrouter/openrouter-large")):
            print(f"\n[{alias}] non-streaming")
            status, headers, raw, secs = chat(args.url, args.key, alias, f"Reply with one short sentence about the number {stamp}.")
            h = {k.lower(): v for k, v in headers.items()}
            check(status == 200, "caller sees 200", f"{status} in {secs:.1f}s")
            check(h.get("x-prism-provider") == expect, "served by OpenRouter", h.get("x-prism-provider"))
            check(h.get("x-prism-fallback") == "true", "x-prism-fallback: true")
            cost = float(h.get("x-prism-cost-usd", "0") or 0)
            check(cost > 0, "cost computed from the provider's usage", f"{cost:.9f} USD")
            if status == 200:
                answer = (json.loads(raw)["choices"][0]["message"].get("content") or "").replace("\n", " ")
                check(bool(answer), "non-empty answer", answer[:70])

            print(f"[{alias}] streaming")
            status, headers, raw, secs = chat(args.url, args.key, alias, f"Count from one to five, stamp {stamp}.", stream=True)
            h = {k.lower(): v for k, v in headers.items()}
            events = [line for line in raw.splitlines() if line.startswith("data: ")]
            check(status == 200 and h.get("x-prism-provider") == expect, "streamed by OpenRouter", h.get("x-prism-provider"))
            check(len(events) > 2 and events[-1] == "data: [DONE]", "SSE chunks, ends with [DONE]", f"{len(events)} events")
    finally:
        print("\n[restore] alpha and beta back up")
        mock_mode(9001, "ok")
        mock_mode(9002, "ok")

    print(f"\n{'ALL CHECKS PASSED' if not failures else str(failures) + ' CHECK(S) FAILED'}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
