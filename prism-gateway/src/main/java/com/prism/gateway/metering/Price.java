package com.prism.gateway.metering;

import java.math.BigDecimal;

/** USD per one million tokens, as in data/model_pricing.json. */
public record Price(BigDecimal inputPer1m, BigDecimal outputPer1m) {
}
