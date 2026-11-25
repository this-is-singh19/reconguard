# Redis Implementation in ReconGuard

## What Redis Is Used For
Redis is the fast, short-lived state layer in ReconGuard.

It is used for:
- webhook idempotency
- reconciliation duplicate suppression
- short-lived matching windows
- eviction of failed claims when retry behavior is needed

Redis is chosen because the project needs low-latency state that does not belong in PostgreSQL.

## Redis In The Ingestion Service
The ingestion controller uses Redis as an idempotency guard.

The key idea is simple:
- the webhook requires an `X-Idempotency-Key`
- the tenant ID is included in the Redis key
- `setIfAbsent` acts like a `SETNX`
- the key is stored with a TTL

If the key already exists, the request is treated as a duplicate and the service returns `202 Accepted` without publishing the event again.

That means duplicate webhook submissions do not create duplicate downstream work.

## Redis In The Reconciliation Engine
The reconciliation engine uses Redis for two more important concerns:

### 1. Idempotency claim checking
Before a Kafka event is processed, the consumer asks Redis whether the event has already been claimed.

If it has:
- the event is dropped
- no database work is done
- the consumer avoids duplicate reconciliation

### 2. Window buffering
If the matching partner for a transaction has not arrived yet, the event is buffered in Redis for a short time.

That allows the engine to:
- hold the first leg while waiting for the second
- reconcile within a controlled window
- keep fast temporary state out of the database

## Why TTL Matters
TTL is central to the Redis design.

The state is intentionally temporary:
- webhook idempotency keys expire after a bounded time
- matching windows expire after a short duration
- failed claims can be evicted so a later retry can proceed

This is the right model for state that should not live forever.

## Tenant Isolation
Redis keys include the tenant context.

That prevents one tenant’s idempotency state or buffered reconciliation data from colliding with another tenant’s data.

This matters because the project is explicitly tenant-aware and the consumer restores tenant context before processing.

## Why Redis Fits Better Than PostgreSQL Here
Redis is the correct tool for this part of the system because the data is:
- temporary
- high-frequency
- frequently checked
- safe to expire

If this state were stored only in PostgreSQL, the system would do unnecessary write-heavy work for data that is useful only for a short window.

## What To Mention In An Interview
The strongest Redis talking points are:

- it prevents duplicate ingestion and duplicate reconciliation
- it supports the short matching window
- it reduces pressure on the database
- it is tenant-scoped and TTL-based
- it helps the system behave predictably under retries

## Short Summary
Redis is used as the fast coordination layer for idempotency and temporary reconciliation state. PostgreSQL remains the durable record, while Redis handles the short-lived workflow mechanics.
