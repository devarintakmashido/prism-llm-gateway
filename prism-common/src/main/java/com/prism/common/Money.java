package com.prism.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money is carried as integer nano-USD. Each request's cost is rounded once, and that
 * same integer is what goes into the response header, the request log and the usage
 * totals, so client-side sums reconcile exactly with the usage API.
 */
public final class Money {

    public static final long NANOS_PER_USD = 1_000_000_000L;

    private Money() {
    }

    public static long usdToNanos(BigDecimal usd) {
        return usd.movePointRight(9).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static BigDecimal nanosToUsd(long nanos) {
        return BigDecimal.valueOf(nanos, 9);
    }

    /** Plain decimal string with 9 fractional digits, e.g. 16050 nanos becomes "0.000016050". */
    public static String format(long nanos) {
        return nanosToUsd(nanos).toPlainString();
    }
}
