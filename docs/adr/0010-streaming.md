# ADR 0010: Streaming pass-through and mid-stream failure

**Status:** accepted

## Pass-through
The gateway opens the upstream stream, waits for the provider's first event (so a provider that fails
before its first token can still fail over), then sends headers (`x-prism-provider`, `x-prism-cache`,
`x-prism-fallback`) and relays each SSE event as it arrives, flushing after every one. Nothing is buffered.
nginx is configured with `proxy_buffering off`, and responses carry `X-Accel-Buffering: no`.

Usage is read from the final chunk (`stream_options.include_usage` is requested for real providers;
the mocks always send it). Cost is recorded, and the log and budget written, **before** the final `[DONE]`.
It is also sent as an SSE comment (`: prism-cost-usd=...`), which every SSE parser ignores.

## Mid-stream failure
If the provider fails after tokens have been sent (connection drop, idle timeout, error chunk), we do
**not** retry on another provider: splicing two models' output into one answer would be silently wrong.
The client receives an error event in the same shape OpenRouter uses, then `[DONE]`:

    data: {"choices":[{"index":0,"delta":{},"finish_reason":"error"}],"error":{"type":"upstream_error","code":"stream_interrupted",...}}

The request is logged as `stream_error`. Tokens already produced are billed (from usage if the provider
sent it, otherwise estimated and flagged `cost_estimated`).

If the client disconnects, the upstream connection is closed at once, so neither we nor the tenant
keep paying for tokens nobody will read (logged as `client_cancelled`).

## A bug the tests caught
The JDK HttpClient's per-request timeout runs until the HttpResponse is complete. With a streaming body,
that means the end of the stream, so a 2 s "first byte" timeout would have cut every answer longer than
2 s. Streams therefore use our own first-byte timeout (waiting for response headers) and idle timeout
(between chunks). `OpenAiCompatibleAdapterTest.aStreamThatGoesSilentIsCutByTheIdleTimeout` covers it.
