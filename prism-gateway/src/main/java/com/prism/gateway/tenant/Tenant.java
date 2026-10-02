package com.prism.gateway.tenant;

import java.util.Set;

/** A team and its virtual key's policy: allowlist, rate limit, monthly budget, cache settings. */
public record Tenant(long id,
                     String team,
                     String keyHint,
                     long monthlyBudgetNanos,
                     int rpmLimit,
                     Integer tpmLimit,
                     Set<String> allowlist,
                     boolean cacheEnabled,
                     Double cacheThreshold,
                     String status) {

    public boolean allows(String model) {
        return allowlist.contains(model);
    }

    public boolean active() {
        return "active".equals(status);
    }
}
