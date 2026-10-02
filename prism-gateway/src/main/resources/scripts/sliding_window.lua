-- Sliding-window request limiter. Runs atomically inside Redis, so concurrent requests
-- (from one gateway instance or many) can never both take the last slot.
--
-- KEYS[1]  the key's window, a sorted set of request ids scored by arrival time (ms)
-- ARGV[1]  window length in ms
-- ARGV[2]  limit (max requests per window)
-- ARGV[3]  unique member for this request (its request id)
--
-- Returns {allowed (1/0), remaining, retry_after_ms}
--
-- Time comes from the Redis server, not the caller, so gateway instances with skewed
-- clocks still share one consistent window.
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local window = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
local count = redis.call('ZCARD', KEYS[1])

if count < limit then
  redis.call('ZADD', KEYS[1], now, ARGV[3])
  redis.call('PEXPIRE', KEYS[1], window)
  return {1, limit - count - 1, 0}
end

local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
local retry_after = window
if oldest[2] then
  retry_after = tonumber(oldest[2]) + window - now
end
return {0, 0, retry_after}
