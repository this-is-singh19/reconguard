# ADR 0002: Spring AI 1.1.8 instead of LangChain4j

- **Status:** Accepted
- **Date:** 2026-11-25
- **Source:** DISCREPANCY_REPORT §2; task 0F.20

## Context

The guide named LangChain4j. The AI worker uses Spring AI through the Ollama starter. The BOM is `org.springframework.ai:spring-ai-bom:1.1.8` in `ai-diagnostic-worker/build.gradle`. The embedding model is `nomic-embed-text`.

## Decision

Use Spring AI (1.1.8) and drop LangChain4j from the design. Update the guide to say Spring AI.

## Consequences

- Provider switching (`reconguard.ai.provider`: `ollama` | `openai`) uses Spring AI conditional beans.
- The guide's LangChain4j references need to be replaced with Spring AI.
- The engine module pulls the worker in as a dependency, so the Spring AI beans load there too.
