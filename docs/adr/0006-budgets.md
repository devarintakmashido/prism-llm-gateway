# ADR 0006: Budgets with reserve-then-settle in one atomic statement

**Status:** accepted

## Context
The final cost is only known after the response (especially for streams), and concurrent requests
must not all see "budget left" at the same time. The seeded budget-demo key has a budget
($0.00001) smaller than one request, yet its first request must succeed and the second must fail.

## Decision
- **Reserve** (before calling a provider): a single `UPDATE usage_monthly ... SET reserved += estimate
  WHERE spent + reserved < budget`. Postgres locks the row, so concurrent admissions serialise.
  The estimate is the worst-case cost of the request across its fallback chain.
- **Settle** (after the response or stream): replace the reservation with the actual cost, in the
  same transaction that writes the request log.
- Calendar months (UTC); a new month is a new row, which is the reset.

## Consequences
- Maximum overshoot: the cost of the last request admitted while budget remained, and concurrent
  requests cannot pile on, because each one's reservation counts against the others. Tested: 20
  concurrent requests on the budget-demo key admit exactly one.
- A request whose admission succeeded is never cut off mid-stream for budget reasons.
- Known limitation: if a gateway process crashes mid-request, its reservation stays until an
  operator clears it (a reservation ledger with expiry would fix this).
