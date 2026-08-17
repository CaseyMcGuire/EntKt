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

## Same-Module Schemas

The current compiled-schema workflow requires a separate schema module. A
source-processing track may remove that requirement while preserving the same
resolved schema graph and generated APIs.

Gradle UX should not assume that direction is already chosen. If it lands, the
plugin should make same-module processing the simple default while retaining an
explicit classpath-schema mode for CLI and multi-module users.

See [Same-Module Schema Processing](same-module-schema-processing.md).

## Test Requirements

Before implementation, add tests for:

- tasks are registered by the Gradle plugin
- `verifyEntktSchemas` works without a database
- stale generated code is detected when configured
- errors include schema and field context
- tasks are cacheable where appropriate
- same-module processing, if selected, avoids compile/generate cycles
- source and classpath schema modes produce equivalent resolved snapshots
