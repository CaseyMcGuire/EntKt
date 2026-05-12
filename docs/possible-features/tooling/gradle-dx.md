# RFC: Gradle Developer Experience

## Status

Possible future feature. This is not implemented.

## Summary

Improve Gradle tasks and diagnostics around schema generation, migration
planning, and project verification.

## Motivation

As entkt grows, users need clear commands for common workflows:

- generate code
- verify schemas compile
- print generated relational schema
- diff schemas
- run migration planning
- fail CI when generated code is stale

## Non-Goals

- Do not hide the existing Gradle plugin APIs.
- Do not require a database for pure codegen verification.
- Do not add interactive prompts in CI paths.
- Do not introduce broad build-system dependencies.

## Proposed Tasks

Potential tasks:

```bash
./gradlew generateEntkt
./gradlew verifyEntktSchemas
./gradlew diffEntktSchemas
./gradlew entktDescribeSchema
./gradlew checkEntktGenerated
```

`checkEntktGenerated` could fail if generated output differs from checked-in
generated files, for projects that commit generated code.

## Diagnostics

Gradle errors should include:

- schema class that failed
- field or edge name when known
- generated file path when generation fails
- suggested next command when migrations are stale

## Test Requirements

Before implementation, add tests for:

- tasks are registered by the Gradle plugin
- `verifyEntktSchemas` works without a database
- stale generated code is detected when configured
- errors include schema and field context
- tasks are cacheable where appropriate

