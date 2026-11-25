# Miscellaneous Implementation Notes

## What This Document Covers
This file explains the supporting pieces around the core services:
- Docker and local infrastructure
- PostgreSQL and schema setup
- Testcontainers and embedded test support
- test utilities and helper patterns

These parts are important because they make the project runnable and testable as a system.

## Docker And Local Infrastructure
`compose.yaml` defines the local runtime stack.

Current services include:
- PostgreSQL
- Redis
- Kafka
- Ollama
- Zipkin

This gives the project a self-contained local environment that matches the architecture of the services.

### Why This Matters
The services are not just theoretical. The compose setup allows a developer to bring up the full supporting stack locally and exercise the application flow end to end.

## Database Setup
PostgreSQL is the durable storage layer for the project.

The repository uses Flyway-backed schema evolution and JPA-based entity persistence in the service layer.

The database is responsible for:
- transaction records
- reconciliation state
- known issues
- resolution suggestions
- failure tracking

The important engineering idea is that durable state lives in PostgreSQL while transient workflow state lives in Redis.

## Testcontainers And Test Profiles
The project uses integration-style testing support, including embedded or container-backed infrastructure where appropriate.

Examples from the current test setup:
- Kafka integration testing with `@EmbeddedKafka`
- H2-based test profile for reconciliation engine tests
- mocked Redis dependencies in some service-level tests
- MVC slice tests for webhook behavior

This shows a layered testing approach:
- fast unit tests for logic
- slice tests for controller behavior
- integration tests for event flow

## Current Test Style
The test code demonstrates a few useful patterns:

### Ingestion tests
The ingestion controller tests verify:
- missing headers return a problem-style 400 response
- fresh requests are accepted and published
- duplicate requests are short-circuited

### Normalization tests
Normalization tests verify:
- trimming and canonical formatting
- tenant context binding
- amount scale and currency casing
- gateway payload conversion

### Reconciliation tests
Reconciliation integration tests verify:
- Kafka-driven matching behavior
- unresolved-to-matched transitions
- database persistence behavior

## Test Utilities And Helper Patterns
The implementation uses several practical testing helpers and patterns:

- `MockMvc` for controller tests
- `Mockito` mocks for service isolation
- `Awaitility` for asynchronous event assertions
- `@AfterEach` cleanup for database state
- tenant context setup and teardown in tests

These utilities matter because much of the project is asynchronous, and test code needs to wait for background processing to complete.

## Why The Test Approach Is Good
The test design is appropriate for this kind of backend because it mixes:
- fast verification of business logic
- HTTP-level validation
- asynchronous event-driven assertions

That gives confidence without requiring every test to be a full end-to-end environment boot.

## Things To Mention In An Interview
The miscellaneous implementation story is strongest when you say:

- Docker provides the local infrastructure stack
- PostgreSQL is the durable data store
- Flyway makes schema changes explicit
- Testcontainers and embedded Kafka help prove the flow in tests
- utility patterns like MockMvc and Awaitility keep tests readable and practical

## Short Summary
The supporting implementation is what makes the project usable in practice: Docker composes the infrastructure, PostgreSQL stores durable data, and the test setup proves the main flows without making the tests overly heavy.
