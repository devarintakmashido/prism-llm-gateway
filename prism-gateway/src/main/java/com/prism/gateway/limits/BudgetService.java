package com.prism.gateway.limits;

import com.prism.common.Money;
import com.prism.gateway.core.PrismError;
import com.prism.gateway.core.PrismException;
import com.prism.gateway.tenant.Tenant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Monthly cost budgets with reserve-then-settle accounting.
 * <p>
 * Admission is one atomic SQL statement: it adds this request's estimated cost to reserved_nanos
 * only if spent + reserved is still below the budget. Postgres locks the usage row for the update,
 * so concurrent requests queue on it instead of all reading "budget left" at once and over-admitting.
 * When the request finishes, {@code settle} swaps the reservation for the real cost.
 * <p>
 * Why "spent + reserved < budget" and not "spent + reserved + estimate <= budget": a budget is a
 * spending cap, and the seeded budget-demo key's budget is smaller than one request, yet its first
 * request must go through. Overshoot is therefore bounded by the last admitted request's cost, and
 * concurrent requests cannot pile on because each one's reservation counts against the others.
 * <p>
 * Budget windows are UTC calendar months; a new month is a new row, which is the reset.
 */
@Service
public class BudgetService {

    public record Reservation(long tenantId, LocalDate month, long reservedNanos) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public BudgetService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    BudgetService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Reservation reserve(Tenant tenant, long estimatedNanos) {
        LocalDate month = currentMonth();
        int admitted = tryReserve(tenant, month, estimatedNanos);
        if (admitted == 0) {
            // Either the budget is spent, or this month's row did not exist yet when we tried (the first
            // requests of a month race each other to create it). Make sure the row exists, whoever
            // creates it, and try exactly once more. Checking "is the row missing?" instead would lose
            // that race: a concurrent first request may create the row between our two statements.
            jdbc.update("INSERT INTO usage_monthly (tenant_id, month) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    tenant.id(), month);
            admitted = tryReserve(tenant, month, estimatedNanos);
        }
        if (admitted == 0) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT spent_nanos FROM usage_monthly WHERE tenant_id = ? AND month = ?", tenant.id(), month);
            long spent = ((Number) row.get("spent_nanos")).longValue();
            throw new PrismException(PrismError.BUDGET_EXCEEDED, String.format(
                    "Monthly budget of $%s exhausted for this key ($%s spent in %s). It resets on the 1st (UTC).",
                    Money.nanosToUsd(tenant.monthlyBudgetNanos()).stripTrailingZeros().toPlainString(),
                    Money.nanosToUsd(spent).stripTrailingZeros().toPlainString(), month.toString().substring(0, 7)));
        }
        return new Reservation(tenant.id(), month, estimatedNanos);
    }

    /** The whole admission decision, as one atomic statement. Returns 1 if admitted, 0 if not. */
    private int tryReserve(Tenant tenant, LocalDate month, long estimatedNanos) {
        return jdbc.update("""
                UPDATE usage_monthly u
                SET reserved_nanos = u.reserved_nanos + ?
                FROM tenants t
                WHERE t.id = u.tenant_id AND u.tenant_id = ? AND u.month = ?
                  AND u.spent_nanos + u.reserved_nanos < t.monthly_budget_nanos
                """, estimatedNanos, tenant.id(), month);
    }

    /**
     * Swaps the reservation for the actual cost. Called inside the same transaction that writes the
     * request log (see RequestLogWriter), so spend and the log can never disagree.
     */
    public void settle(Reservation r, long actualNanos, int promptTokens, int completionTokens, boolean billable) {
        jdbc.update("""
                UPDATE usage_monthly
                SET reserved_nanos    = GREATEST(reserved_nanos - ?, 0),
                    spent_nanos       = spent_nanos + ?,
                    requests          = requests + ?,
                    prompt_tokens     = prompt_tokens + ?,
                    completion_tokens = completion_tokens + ?
                WHERE tenant_id = ? AND month = ?
                """, r.reservedNanos(), actualNanos, billable ? 1 : 0, promptTokens, completionTokens,
                r.tenantId(), r.month());
    }

    LocalDate currentMonth() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }
}
