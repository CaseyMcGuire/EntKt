# RFC: Same-Module Schema Processing

## Status

Exploration note. No processor technology or migration path has been selected.

## Summary

Explore source-based schema processing so an application can declare EntKt
schemas and consume generated APIs in the same Gradle module.

The likely implementation candidate is KSP, but this RFC records the desired
contract rather than committing to a processor before a prototype measures
compatibility, diagnostics, and build performance.

## Motivation

The current classpath scanner needs compiled schema classes before generating
the application-facing code. If schemas and generated output share a module,
that creates a compile/generate cycle, so users must maintain a separate schema
module.

The split is workable for large projects but disproportionate for small
applications and examples. It also limits source-position diagnostics and
incremental processing.

## Goals

- allow schema declarations and generated clients in one module
- preserve explicit table, column, edge, and index declarations
- report errors at source declarations when possible
- support incremental and cacheable Gradle builds
- produce the same resolved schema model as classpath generation
- keep migration and schema-explain tooling independent of application startup
- avoid requiring a compiler plugin when a simpler processor is sufficient

## Proposed User Experience

```kotlin
plugins {
    kotlin("jvm")
    id("entkt")
}

dependencies {
    implementation("io.entkt:schema:$entktVersion")
    implementation("io.entkt:runtime:$entktVersion")
    implementation("io.entkt:postgres:$entktVersion")
}

entkt {
    packageName.set("com.example.ent")
}
```

Schemas can live under the ordinary main source set:

```text
src/main/kotlin/com/example/schema/User.kt
src/main/kotlin/com/example/schema/Post.kt
```

Generated sources remain under `build/generated/` and participate in the same
compilation without a separate `schemas(project(...))` configuration.

## One Resolved Schema Model

Source processing must feed the existing finalization concepts rather than
invent a second schema language.

The processor builds a serializable declaration graph containing:

- schema and declaration names
- explicit storage names
- field types and modifiers
- typed target references
- inverse and junction property references
- indexes, validation metadata, and native storage requirements
- source locations for diagnostics

A shared resolver then performs the same cross-schema validation used by
codegen, schema explanation, and migration tooling.

Runtime construction side effects must not become part of schema meaning.

## Processor Boundaries

Source processing should generate metadata and typed code; it should not run
database I/O or load the application.

Features that rely on arbitrary runtime lambdas need an explicit representation:

- store a generated reference when runtime code owns the callback
- store structured metadata for constraints used by DDL or tooling
- reject declarations that cannot be represented consistently

The prototype must verify support for Kotlin types used by JSON fields, enums,
mixins, generics, and property references.

## Incrementality

The processor should distinguish isolating entity output from aggregating
schema-graph output.

- entity types and local builders may regenerate only for the changed schema
- client, cross-schema edges, collision manifests, and full schema snapshots are
  aggregating outputs
- generation order and output content remain deterministic
- cache keys include processor version and relevant configuration

The design should measure real Gradle configuration-cache and incremental-build
behavior rather than merely declaring tasks cacheable.

## Compatibility And Migration

The existing compiled-schema path may remain temporarily for CLI use and
projects that prefer a dedicated schema module. Both frontends must produce the
same resolved intermediate model and golden output.

Do not maintain two independent code generators.

A migration path can be:

1. prototype source extraction for fields and direct edges
2. compare resolved metadata against the classpath scanner
3. add cross-schema references, mixins, JSON types, and indexes
4. integrate same-module Gradle wiring
5. make source processing the documented default only after parity

## Diagnostics

Errors should identify:

- source file and declaration
- related declaration for cross-schema mismatches
- generated member involved in a collision
- suggested correction when one is unambiguous

Schema explanation should include source attribution without exposing absolute
paths in reproducible machine-readable output.

## Non-Goals

- Do not infer SQL names from Kotlin property names.
- Do not weaken final schema-graph validation.
- Do not require runtime reflection for generated application queries.
- Do not introduce a compiler plugin unless a processor prototype proves
  insufficient.
- Do not add KSP dependencies to runtime modules.
- Do not remove dedicated schema-module support before parity is established.

## Decision Gate

Before choosing KSP or another mechanism, prototype and measure:

- support for every current schema declaration shape
- source error quality
- clean and incremental build time
- Gradle configuration-cache behavior
- generated-output determinism
- compatibility across supported Kotlin versions
- maintenance cost relative to the classpath scanner

## Test Requirements

- same-module schemas compile and expose generated clients
- source and classpath frontends produce equivalent resolved schema snapshots
- cross-schema changes invalidate every affected aggregating output
- unchanged schemas do not regenerate isolating outputs
- diagnostics carry correct source declarations
- clean builds are deterministic across directories
- migration and explain tasks consume the shared resolved model
- runtime modules have no processor dependency

## Related Features

- [Gradle Developer Experience](gradle-dx.md)
- [Thin Codegen And Runtime Execution Engines](thin-codegen-runtime-engines.md)
- [Codegen Plugin Hooks](codegen-plugin-hooks.md)
