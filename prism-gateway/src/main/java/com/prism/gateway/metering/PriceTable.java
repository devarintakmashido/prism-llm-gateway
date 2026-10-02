package com.prism.gateway.metering;

import com.prism.gateway.config.GatewayConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The model price table, read from Postgres (seeded from data/model_pricing.json) and refreshed
 * every minute. At startup it checks that every model in every alias chain has a price: a chain
 * that could route to an unpriced model would make metering silently wrong, so we refuse to start.
 */
@Component
@DependsOn("seedData")
public class PriceTable {

    private static final Logger log = LoggerFactory.getLogger(PriceTable.class);

    private final JdbcTemplate jdbc;
    private volatile Map<String, Price> prices;

    public PriceTable(JdbcTemplate jdbc, GatewayConfig config) {
        this.jdbc = jdbc;
        this.prices = load();
        List<String> unpriced = config.aliasChains().values().stream().flatMap(List::stream)
                .map(GatewayConfig.ModelRoute::model).filter(m -> !prices.containsKey(m)).distinct().toList();
        if (!unpriced.isEmpty()) {
            throw new IllegalStateException("Alias chains reference models without a price: " + unpriced);
        }
        log.info("Price table loaded: {}", prices.keySet());
    }

    public Optional<Price> price(String model) {
        return Optional.ofNullable(prices.get(model));
    }

    public boolean isPriced(String model) {
        return prices.containsKey(model);
    }

    public long costNanos(String model, long promptTokens, long completionTokens) {
        Price price = price(model).orElseThrow(() -> new IllegalStateException("No price for model " + model));
        return CostCalculator.costNanos(price, promptTokens, completionTokens);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void refresh() {
        try {
            prices = load();
        } catch (RuntimeException e) {
            log.warn("Could not refresh price table, keeping the previous one: {}", e.getMessage());
        }
    }

    private Map<String, Price> load() {
        return jdbc.query("SELECT model, input_per_1m, output_per_1m FROM model_prices",
                        (rs, i) -> Map.entry(rs.getString("model"),
                                new Price(rs.getBigDecimal("input_per_1m"), rs.getBigDecimal("output_per_1m"))))
                .stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
