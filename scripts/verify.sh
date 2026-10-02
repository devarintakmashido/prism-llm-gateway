#!/usr/bin/env bash
# Runs every verification step against a running Prism stack and saves the outputs to reports/.
#
#   docker compose up -d --build
#   ./scripts/verify.sh
#
# Steps: pack validation, smoke test (twice: the cache must stay correct across runs), load test
# reconciled against the usage API, routing eval on the graded and held-out sets, streaming pacing
# through the edge proxy, and the live failure drill.
set -uo pipefail
cd "$(dirname "$0")/.."

URL="${PRISM_URL:-http://localhost:8080}"
ADMIN="${PRISM_ADMIN_TOKEN:-prism-admin-dev-token}"
OUT=reports
mkdir -p "$OUT"
status=0

step() { printf '\n==== %s ====\n' "$1"; }
run() { # run <name> <command...>: tee output to reports/<name>.txt, remember failures
  local name=$1; shift
  if "$@" 2>&1 | tee "$OUT/$name.txt"; then echo "[ok] $name"; else echo "[FAILED] $name"; status=1; fi
}

step "0. Pack validation"
run validate_pack python3 scripts/validate_pack.py

step "1. Smoke test (run 1)"
run smoke_test_run1 python3 scripts/smoke_test.py --url "$URL" --key prism-sk-search-1a2b3c --model fast
step "1b. Smoke test (run 2: same prompts, new salt - must still pass)"
run smoke_test_run2 python3 scripts/smoke_test.py --url "$URL" --key prism-sk-search-1a2b3c --model fast

step "2. Load test + reconciliation with the usage API"
run load_test_reconciliation python3 scripts/reconcile.py --url "$URL" --admin-token "$ADMIN" --wait-for-window

step "2b. Semantic cache on the provided sample pairs (A, near-miss A3, B, no-cache)"
run cache_pairs python3 scripts/cache_pairs.py --url "$URL" --admin-token "$ADMIN"

step "3. Routing eval"
run routing_eval python3 scripts/routing_eval.py --url "$URL" --admin-token "$ADMIN" --json "$OUT/routing_eval.json"
run routing_heldout python3 scripts/routing_eval.py --url "$URL" --admin-token "$ADMIN" --cases data/routing_heldout.jsonl
run routing_heldout_v2 python3 scripts/routing_eval.py --url "$URL" --admin-token "$ADMIN" --cases data/routing_heldout_v2.jsonl
run routing_heldout_external python3 scripts/routing_eval.py --url "$URL" --admin-token "$ADMIN" --cases data/routing_heldout_external.jsonl

step "4. Streaming through the edge proxy (tokens must arrive progressively)"
run streaming_pacing python3 - "$URL" <<'EOF'
import json, sys, time, urllib.request
url = sys.argv[1]
req = urllib.request.Request(url + "/v1/chat/completions", headers={"Content-Type": "application/json",
      "Authorization": "Bearer prism-sk-research-4d5e6f"},
      data=json.dumps({"model": "fast", "stream": True, "messages": [{"role": "user",
            "content": "Explain how server-sent events work, in detail please."}]}).encode())
t0 = time.monotonic(); stamps = []
with urllib.request.urlopen(req) as r:
    for raw in r:
        if raw.startswith(b"data: "):
            stamps.append((time.monotonic() - t0) * 1000)
spread = stamps[-1] - stamps[0]
print(f"{len(stamps)} data events; first at {stamps[0]:.0f} ms, last at {stamps[-1]:.0f} ms (spread {spread:.0f} ms)")
print("PASS: progressive" if spread > 200 else "FAIL: arrived all at once (buffered)")
sys.exit(0 if spread > 200 else 1)
EOF

step "5. Failure drill (alpha down / slow / flaky / restored)"
run failure_drill python3 scripts/failure_drill.py --url "$URL" --alpha http://localhost:9001

step "Summary"
echo "Reports written to $OUT/"
[ $status -eq 0 ] && echo "ALL VERIFICATION STEPS PASSED" || echo "SOME STEPS FAILED (see above)"
exit $status
