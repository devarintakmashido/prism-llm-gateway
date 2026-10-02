package com.prism.gateway.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.prism.gateway.config.GatewayConfig.Provider;

import java.time.Duration;

/**
 * The only way the gateway talks to a model provider. The core never sees a provider's wire
 * format, so a mock, a real OpenAI-compatible API, or a provider with a different protocol can
 * be plugged in without touching routing, metering or caching.
 */
public interface ProviderAdapter {

    /** Timeouts for one call: until response headers, for the whole call, and between stream chunks. */
    record CallOptions(Duration firstByteTimeout, Duration totalTimeout, Duration streamIdleTimeout) {
    }

    /** A non-streaming completion. The body is kept byte-for-byte so it can be passed through unchanged. */
    Completion complete(Provider provider, String model, ObjectNode request, CallOptions options) throws UpstreamException;

    /**
     * Opens a stream and returns once the provider has accepted it and sent its first event, so any
     * failure up to that point can still fail over to another provider.
     */
    UpstreamStream stream(Provider provider, String model, ObjectNode request, CallOptions options) throws UpstreamException;
}
