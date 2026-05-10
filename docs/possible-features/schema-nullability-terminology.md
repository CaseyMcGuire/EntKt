# RFC: Schema Nullability Terminology

## Status

Possible future feature. This is not implemented.

## Summary

Standardize the schema DSL on `.nullable()` for nullable storage and generated
Kotlin nullability. Remove `.optional()` as an alias across the schema DSL.

This applies to scalar fields and relationships:

```kotlin
val bio = text("bio").nullable()
val author = belongsTo<User>("author").nullable()
```

## Motivation

The word "optional" is overloaded:

- optional in create input
- optional in update input
- nullable in storage
- nullable in generated Kotlin

The DSL modifier should describe the schema invariant directly. `.nullable()`
says that the stored value and generated Kotlin type may be null. Keeping both
`.optional()` and `.nullable()` invites drift in implementation, diagnostics,
and examples.

## Proposed Contract

Use `.nullable()` as the only nullability modifier in the schema DSL.

- scalar fields are non-null by default and become nullable with `.nullable()`
- `belongsTo(...)` relationships are non-null by default and become nullable
  with `.nullable()`
- `.optional()` should be removed from field and relationship builders
- `.required()` should not be used for required-by-default relationships

If `.optional()` or relationship `.required()` methods still exist during
migration, schema validation should reject their use and direct callers to the
new contract.

## Examples

Scalar fields:

```kotlin
val displayName = string("display_name")
val bio = text("bio").nullable()
```

To-one relationships:

```kotlin
val author = belongsTo<User>("author")
val editor = belongsTo<User>("editor").nullable()
```

## Relationship To Edge Mutation RFCs

[To-One Assignment And Nullability](edge-mutation-to-one-assignment-nullability.md)
depends on this terminology: `belongsTo(...)` is required by default, and
`.nullable()` is the only way to make a to-one relationship nullable.

[Many-To-Many Schema Modeling](edge-mutation-m2m-schema-modeling.md) uses the
same terminology for junction `belongsTo` edges.

## Rollout Plan

1. Add `.nullable()` examples to docs where nullable fields or relationships are
   introduced.
2. Remove `.optional()` from field builders, or keep it only long enough for
   schema validation to reject it with a clear migration message.
3. Remove relationship `.required()` from edge builders, or reject it during
   migration because `belongsTo(...)` is required by default.
4. Update diagnostics and docs to use "nullable" rather than "optional" for
   schema nullability.

## Test Requirements

Before implementation, add tests for:

- scalar fields use `.nullable()` for nullable generated Kotlin and storage
  metadata
- `belongsTo(...)` relationships are non-null by default
- `belongsTo(...).nullable()` produces nullable FK and relationship mutation
  types
- `.optional()` on scalar field builders is rejected or removed
- `.optional()` on relationship builders is rejected or removed
- `.required()` on `belongsTo(...)` is rejected or removed
- diagnostics consistently use "nullable" for schema nullability
