package com.prism.gateway.metering;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cost of one request in nano-USD, from provider-reported token usage.
 * <p>
 * A price of $X per 1M tokens is X * 1e9 / 1e6 = X * 1000 nano-USD per token. The sum is computed in
 * exact decimal arithmetic and rounded once (half-up) to a whole number of nanos. That single
 * integer is used everywhere afterwards (header, log, budget, usage API), which is why the totals
 * reconcile exactly.
 */
public final class CostCalculator {

    private static final BigDecimal NANOS_PER_TOKEN_PER_USD_PER_1M = BigDecimal.valueOf(1000);

    private CostCalculator() {
    }

    public static long costNanos(Price price, long promptTokens, long completionTokens) {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts must be non-negative");
        }
        BigDecimal usd1mTokens = price.inputPer1m().multiply(BigDecimal.valueOf(promptTokens))
                .add(price.outputPer1m().multiply(BigDecimal.valueOf(completionTokens)));
        return usd1mTokens.multiply(NANOS_PER_TOKEN_PER_USD_PER_1M)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
