# API rate limiting

Ultimate enforces independent one-minute request budgets for public authentication, expensive AI work, and administrative mutations. Enforcement happens inside the reactive Spring Security chain after JWT authentication, so authenticated callers are isolated by principal while anonymous authentication traffic is isolated by client address.

## Protected request classes

| Class | Requests | Default budget |
| --- | --- | ---: |
| `AUTH` | Mutating `/api/v1/auth/**` requests | 5/minute per client address |
| `CHAT` | Mutating chat, agent, voice, memory, and document requests | 30/minute per authenticated principal |
| `ADMIN` | `/api/v1/admin/**` plus mutating settings requests | 10/minute per principal |

`OPTIONS`, health checks, API documentation, static assets, and ordinary read-only API traffic are bypassed.

## Configuration

The existing `ultimate.security.rate-limiting` namespace controls enforcement:

```yaml
ultimate:
  security:
    rate-limiting:
      enabled: true
      chat-requests-per-minute: 30
      auth-attempts-per-minute: 5
      admin-requests-per-minute: 10
      max-tracked-keys: 10000
      trust-forwarded-headers: false
```

`max-tracked-keys` strictly bounds in-memory caller state. Expired windows are removed opportunistically and release capacity.

Keep `trust-forwarded-headers` disabled unless the application is behind a trusted reverse proxy that strips inbound `Forwarded` and `X-Forwarded-For` values and writes its own. When enabled, Ultimate uses the first forwarded address; otherwise it uses the direct remote address.

## Response contract

Admitted protected requests receive:

- `RateLimit-Limit`
- `RateLimit-Remaining`
- `RateLimit-Reset` (seconds until the next window)

Exhausted requests receive HTTP `429 Too Many Requests`, the same rate-limit fields, a `Retry-After` value, and a JSON body with stable code `RATE_LIMIT_EXCEEDED`.

## Deployment note

The limiter is intentionally process-local for Ultimate's local-first, single-node runtime. A future multi-node deployment should replace the counter backend with an atomic shared store while preserving the filter and response contract.
