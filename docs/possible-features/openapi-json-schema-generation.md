# RFC: OpenAPI / JSON Schema Generation

## Status

Possible future feature. This is not implemented.

## Summary

Generate OpenAPI or JSON Schema definitions from entkt schemas and generated
DTO conventions.

## Motivation

entkt schema metadata includes field names, scalar types, nullability,
required fields, enum values, IDs, and relationships. That is enough to
produce useful API schema starting points for sample projects.

## Non-Goals

- Do not generate a complete REST server in the first version.
- Do not assume database entities are always public API types.
- Do not expose private fields by default.
- Do not replace application-owned API design.

## Proposed Output

Generate JSON Schema components:

```yaml
components:
  schemas:
    Post:
      type: object
      required: [id, title, authorId]
      properties:
        id:
          type: integer
          format: int64
        title:
          type: string
        authorId:
          type: integer
          format: int64
```

Optional OpenAPI output could wrap these components in a minimal document.

## Configuration

Potential Gradle configuration:

```kotlin
entkt {
    openApi {
        enabled.set(true)
        outputFile.set(layout.buildDirectory.file("entkt/openapi.yaml"))
    }
}
```

The first version should focus on schemas only. Path generation requires
application-specific routing knowledge.

## Privacy Behavior

OpenAPI generation should not imply a field is visible to every viewer. The
generated docs should include a note that runtime privacy remains enforced by
entkt APIs and application controllers.

## Test Requirements

Before implementation, add tests for:

- scalar fields map to JSON Schema types
- nullable and required fields are represented correctly
- enums include allowed values
- IDs include useful formats
- output is deterministic
- generated schema does not include internal edge containers by default

