# ADR 0007: Money as integer nano-USD

**Status:** accepted

## Decision
All costs are stored and summed as integer nano-USD (1 USD = 10^9). A price of $X per 1M tokens
is exactly X * 1000 nano-USD per token. Each request's cost is computed in exact decimal and
rounded once (half-up). That one integer is used for the `x-prism-cost-usd` header (9 decimals),
the request log, the budget and the usage API.

## Why
Floating point (Java `double`, Redis `INCRBYFLOAT`) drifts over thousands of additions, and the
load test compares the sum of client-side headers with the usage API. With integers, it
reconciles exactly.
