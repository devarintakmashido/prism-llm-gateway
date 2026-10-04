# Submission summary

Prism is an OpenAI-compatible LLM gateway: virtual keys, Redis rate limiting, reserve-then-settle budgets,
smart routing (`model: auto`), a per-team semantic cache and provider failover, with an ops console.

| Deliverable | Where |
|---|---|
| Source, Docker setup | this repository (`docker compose up -d --build`) |
| README (setup, API, architecture, routing/failover design, cache scoping, design decisions) | [README.md](../README.md) |
| Seeded demo keys and how to reproduce the demo with the mock providers | README, "Quick start" and "Demo walkthrough" |
| Verification report (smoke test, load test, routing eval, over-admission, accounting, latency) | [docs/VERIFICATION_REPORT.md](VERIFICATION_REPORT.md); reproduce with `./scripts/verify.sh` |
| Architecture decisions | [docs/adr](adr) |
| Demo video | uploaded in the submission form |

Quick check after cloning:

```bash
docker compose up -d --build
curl -s localhost:8080/health          # alpha and beta healthy
./scripts/verify.sh                    # smoke, load, routing, cache, failure drill -> reports/
```

The optional OpenRouter provider is off unless an API key is set; the graded setup uses only the two mock providers.
