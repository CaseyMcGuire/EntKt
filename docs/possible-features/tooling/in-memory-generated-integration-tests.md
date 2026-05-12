# RFC: In-Memory Generated Integration Tests

## Status

Possible future feature. This is not implemented.

## Summary

Add a fast integration test suite that compiles generated entkt code and runs
it against `InMemoryDriver`.

## Motivation

Postgres integration tests are valuable but slower and require Docker.
Codegen string tests are fast but do not prove runtime behavior.

An in-memory generated integration suite would sit between them:

- compile real generated code
- run public APIs
- avoid Docker
- cover privacy, validation, hooks, edges, and candidates quickly

## Non-Goals

- Do not remove Postgres integration tests.
- Do not pretend `InMemoryDriver` catches SQL dialect bugs.
- Do not duplicate every Postgres driver test.
- Do not add external dependencies.

## Proposed Shape

Add a module:

```text
integration-tests-inmemory
```

or add a second source set to the existing integration test module:

```text
src/inMemoryTest/kotlin
```

The suite should run generated code against `InMemoryDriver` and use small
schemas focused on behavior.

## Coverage Targets

Good first tests:

- privacy LOAD strict reads
- create/update/delete privacy
- validation once implemented
- edge eager loading
- edge mutation once implemented
- hooks order
- bulk convenience methods

## Test Requirements

Before implementation, add tests for:

- generated code compiles in the in-memory integration module
- core privacy behavior matches Postgres integration tests
- suite runs without Docker
- CI can run this module before slower Postgres tests

