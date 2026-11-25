# Phase 2.5 - Ingestion and Reconciliation Gaps

## Summary
This phase fills the gaps between the guide's ingestion design and the current implementation: webhook normalization, idempotency, error contracts, tenant propagation, dead-letter handling, schema reconciliation, and tenant provisioning.

## Status
Project view: 4 implemented, 4 partial, 2 not implemented.

### Implemented currently
- [x] Enforce `X-Idempotency-Key` header on the webhook endpoint and return a cached `202` response for duplicate keys.
- [x] Add RFC 7807 `application/problem+json` error responses to the ingestion endpoint, including HMAC failures.
- [x] Configure `DefaultErrorHandler` with exponential back-off and a dead-letter topic `recon.dlq.{tenantId}` per consumer.
- [x] Add Flyway migrations for `known_issues` (V4) and `resolution_suggestions` (V5) so Phase 3 has a schema foundation to build on.

### Partial
- [ ] Add `PayloadNormalizationService` to map gateway-specific schemas to the canonical `TransactionEvent` before publishing to Kafka.
- [ ] Verify the webhook controller returns the correct `202 Accepted` response shape (`ingestion_id`, `received_at`).
- [ ] Wire `TenantContextHolder` propagation inside the Kafka consumer so all repository calls are automatically tenant-scoped.
- [ ] Resolve the `correlation_key` vs guide schema conflict - decide whether the `transactions` table uses the current migration columns or the guide's columns (`order_id`, `gateway_transaction_id`, `amount`, `currency`, `order_ts`, `payment_ts`, `status`) and rewrite the migration accordingly.

### No implementation
- [ ] Add FX rate normalization before amount tolerance comparison - cache the rate and refresh every 60 seconds.
- [ ] Add a `TenantProvisioningService` that creates the `LIST (tenant_id)` partition for a new tenant at runtime rather than in Flyway.

## Detailed Tasks
### In place
- Keep the ingestion endpoint centered on the guide's webhook flow, including HMAC verification before downstream processing.
- Keep canonical event conversion as a separate concern from Kafka publishing.
- Keep tenant-aware processing explicit rather than implied.

### Remaining work
- Normalize gateway payloads before they reach the Kafka producer.
- Standardize duplicate webhook handling on `X-Idempotency-Key`.
- Return the guide's accepted response payload and problem-details errors.
- Define and enforce the retry and dead-letter policy for consumer failures.
- Close the schema ambiguity by choosing one transaction table shape and aligning migrations with it.
- Add the tables needed by Phase 3 before that phase starts.
- Introduce runtime tenant provisioning instead of forcing all tenant partitions into initial migrations.

## Acceptance Criteria
- Ingestion produces the guide's canonical event shape and response contract.
- Duplicate webhook submissions return a cached accepted response.
- Ingestion and consumer failures are surfaced as RFC 7807 responses or DLQ events where appropriate.
- Schema and tenant provisioning are ready for the AI worker phase.
