# RFC: Generated Member Name Collisions

## Status

Possible future feature. This is not implemented.

Extracted from the implemented
[To-One FK Mutation And Nullability](../../implemented-features/edge-mutation/02-to-one-assignment-nullability.md)
RFC.

## Summary

Codegen should validate all generated member names that share a Kotlin artifact
namespace, not just generated property names. The current validation catches the
highest-frequency field/edge/FK property collisions, but it does not fully cover
generated helper methods such as `unset{Property}()` or hook-facing view
members.

## Motivation

Generated APIs are intentionally small and predictable. If a schema declaration
collides with a generated method or view member, the failure should happen at
schema/codegen validation time with a clear diagnostic, not later as a Kotlin
compiler error or a surprising hidden member.

Examples that should be rejected:

```kotlin
val name = string("name")
val unsetName = bool("unset_name")
```

because `unsetName` collides with the generated `unsetName()` method on
`${Entity}UpdateMutationView`.

```kotlin
val pendingEdges = string("pending_edges")
val tags = manyToMany<Tag>("tags").throughLink<PostTag>(PostTag::post, PostTag::tag)
```

because helper-eligible M2M edges add `pendingEdges` to the update mutation
view.

## Design

Codegen should build generated-name manifests per schema and per artifact. A
manifest entry should record:

- the generated artifact, such as entity, create builder, update builder,
  mutation interface, create mutation view, update mutation view, patch class,
  pending-edge-ops class, or edge refs
- the member name
- the member kind, such as property, function, nested type, or constructor
  parameter
- the schema declaration that caused it

Validation should reject duplicate member names within each artifact namespace.
It should also reject schema declarations that collide with fixed framework
members on those artifacts.

## Required Coverage

The first implementation should cover:

- scalar field properties
- edge properties
- implicit FK properties
- field-backed FK properties
- `unset{Property}()` methods for mutable update fields and FKs
- `pendingEdges` on update mutation views
- fixed builder members such as `save`, `saveOrError`, `saveOrThrow`, `client`,
  `driver`, hook lists, `entity`, and `dirtyFields`

## Diagnostics

Errors should name both sides of the collision and the generated artifact:

```text
Schema 'Post': field 'unset_author_id' generates member 'unsetAuthorId' on
PostUpdateMutationView, which collides with generated unset method for
synthesized FK for edge 'author'
```

## Acceptance Criteria

- Collisions involving generated `unset{Property}()` methods are rejected.
- Collisions involving fixed hook-facing view members are rejected.
- Diagnostics identify the schema, generated artifact, generated name, and both
  source declarations.
- Existing valid schemas continue to pass without renaming.
