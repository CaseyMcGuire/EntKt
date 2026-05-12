# RFC: Codegen Plugin Hooks

## Status

Possible future feature. This is not implemented.

## Summary

Add extension points that let users or companion modules generate additional
files from entkt schema metadata.

## Motivation

Potential future generators include:

- GraphQL DTOs
- OpenAPI schemas
- test fixtures
- JSON mappers
- repository extensions
- project-specific code conventions

Without plugin hooks, every extension requires changing entkt core.

## Non-Goals

- Do not expose unstable internal generator details as public API.
- Do not allow plugins to mutate core generated files in the first version.
- Do not add dynamic class loading until the Gradle UX is clear.
- Do not make codegen order-dependent without explicit dependencies.

## Proposed API

Programmatic interface:

```kotlin
interface EntktCodegenPlugin {
    fun generate(context: CodegenPluginContext): List<GeneratedFile>
}

data class CodegenPluginContext(
    val schemas: List<SchemaInput>,
    val packageName: String,
    val normalizedSchema: List<EntitySchema>,
)
```

Gradle configuration:

```kotlin
entkt {
    plugins {
        register(MyGraphqlPlugin())
    }
}
```

## Safety

Plugin outputs should be written under a separate generated directory by
default:

```text
build/generated/entkt/plugins/<plugin-name>
```

Core codegen should remain deterministic when no plugins are configured.

## Test Requirements

Before implementation, add tests for:

- plugin receives schema metadata
- plugin writes generated files
- plugin output participates in compilation when configured
- plugin failures surface useful Gradle errors
- core generation is unchanged when no plugins are configured

