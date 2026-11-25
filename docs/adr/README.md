# Architecture Decision Records

Format: Status / Context / Decision / Consequences. Status values:

- **Accepted** – decision matches the code today.
- **Accepted – implementation planned** – decision adopted; code does not do it yet (task ID in brackets).

| # | Title | Status |
|---|---|---|
| 0001 | Spring Boot 3.5.13 line | Accepted |
| 0002 | Spring AI 1.1.8 instead of LangChain4j | Accepted |
| 0003 | VARCHAR(64) natural ids | Accepted |
| 0004 | `known_issues` column names | Accepted (threshold and tenant columns planned, G1.9) |
| 0005 | Single 0.90 / 0.75 AI threshold set (0.85 dropped) | Accepted |
| 0006 | Matching tolerances: amount 0.02, time 120 s | Accepted (per-tenant planned, G2.3) |
| 0007 | Webhook signature header `X-Webhook-Signature: sha256=` | Accepted – implementation planned (G5.1) |
| 0008 | `messageId` on the canonical event | Accepted – implementation planned (G1.4) |
| 0009 | Producer `enable.idempotence=true` | Accepted – implementation planned (A4.8) |
| 0010 | Topic partition counts | Accepted – implementation planned (A3.15) |
