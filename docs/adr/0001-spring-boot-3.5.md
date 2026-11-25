# ADR 0001: Spring Boot 3.5.13 line

- **Status:** Accepted
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide said "Spring Boot 3.3+". The code uses Boot 3.5.13 through the root `build.gradle` plugin version. The `common` module also pins to the same BOM, and the Spring AI BOM is aligned to it.

## Decision

Use the Spring Boot 3.5.x line, pinned to 3.5.13. Update the guide to match the code.

## Consequences

- The guide's "3.3+" becomes "3.5.13".
- Upgrades are done by changing the single root plugin version.
- Java 21 toolchain stays as is.
