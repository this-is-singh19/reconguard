# ADR 0007: Webhook signature header `X-Webhook-Signature: sha256=`

- **Status:** Accepted – implementation planned (G5.1)
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide specifies `X-Webhook-Signature: sha256=<hmac>`. The code reads `X-Signature` in `HmacSecurityInterceptor` (line 38) and expects a bare hex HMAC-SHA256 of the body (`HmacUtils.calculateHmac`). There is no `sha256=` prefix and no timestamp. The current check is wired and tested with `X-Signature` in `HmacVerificationTest`.

## Decision

Adopt the guide's header name `X-Webhook-Signature` and its `sha256=` prefix. The timestamp part comes with G5.1. The code is not changed by this ADR.

## Consequences

- Clients will send `X-Webhook-Signature: sha256=<hex>` once G5.1 lands.
- Until then the running code expects `X-Signature` with no prefix. Docs must say "planned" for the new header.
- `HmacVerificationTest` and the interceptor change in G5.1, not here.
