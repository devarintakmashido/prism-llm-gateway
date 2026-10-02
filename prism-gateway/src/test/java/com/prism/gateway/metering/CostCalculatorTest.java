package com.prism.gateway.metering;

import com.prism.common.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostCalculatorTest {

    private static final Price ALPHA_SMALL = new Price(new BigDecimal("0.15"), new BigDecimal("0.60"));
    private static final Price ALPHA_LARGE = new Price(new BigDecimal("3.00"), new BigDecimal("15.00"));

    @Test
    void pricesPerMillionTokensBecomeNanoUsd() {
        // 11 prompt + 24 completion tokens on alpha-small: 11*0.15 + 24*0.60 = 16.05 USD per 1M = 16,050 nanos
        assertThat(CostCalculator.costNanos(ALPHA_SMALL, 11, 24)).isEqualTo(16_050);
        assertThat(Money.format(16_050)).isEqualTo("0.000016050");
    }

    @Test
    void usesProviderTokenSplitNotTotal() {
        // Output tokens cost 5x input on alpha-large; swapping the split must change the cost.
        assertThat(CostCalculator.costNanos(ALPHA_LARGE, 100, 10)).isEqualTo(450_000);
        assertThat(CostCalculator.costNanos(ALPHA_LARGE, 10, 100)).isEqualTo(1_530_000);
    }

    @Test
    void zeroTokensCostNothing() {
        assertThat(CostCalculator.costNanos(ALPHA_SMALL, 0, 0)).isZero();
    }

    @Test
    void roundsHalfUpOnceToWholeNanos() {
        Price odd = new Price(new BigDecimal("0.0375"), new BigDecimal("0.0005")); // 37.5 and 0.5 nanos per token
        assertThat(CostCalculator.costNanos(odd, 1, 0)).isEqualTo(38);
        assertThat(CostCalculator.costNanos(odd, 0, 1)).isEqualTo(1);
        assertThat(CostCalculator.costNanos(odd, 2, 0)).isEqualTo(75);
    }

    @Test
    void sumsOfRoundedCostsMatchTheLedgerExactly() {
        // Every request is rounded once; the header, the log and the usage total all use that integer.
        long total = 0;
        for (int i = 1; i <= 1000; i++) {
            total += CostCalculator.costNanos(ALPHA_SMALL, i % 37, i % 53);
        }
        long recomputed = 0;
        for (int i = 1; i <= 1000; i++) {
            recomputed += Money.usdToNanos(new BigDecimal(Money.format(CostCalculator.costNanos(ALPHA_SMALL, i % 37, i % 53))));
        }
        assertThat(recomputed).isEqualTo(total);
    }

    @Test
    void rejectsNegativeTokenCounts() {
        assertThatThrownBy(() -> CostCalculator.costNanos(ALPHA_SMALL, -1, 5)).isInstanceOf(IllegalArgumentException.class);
    }
}
