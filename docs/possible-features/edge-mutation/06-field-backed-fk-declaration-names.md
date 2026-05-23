# RFC: Field-Backed FK Declaration Names

## Status

Possible future feature. This is not implemented.

Extracted from the implemented
[To-One FK Mutation And Nullability](../../implemented-features/edge-mutation/02-to-one-assignment-nullability.md)
RFC.

## Summary

Field-backed `belongsTo(...).field(handle)` relationships should name their
generated FK API from the Kotlin property declaration that produced the backing
field handle, not from the backing column string.

Today the storage column remains correct, but the public API is derived with
`toCamelCase(field.name)`. That makes a field such as:

```kotlin
val writer = uuid("author_id")
val author = belongsTo<User>("author").field(writer)
```

generate `authorId`-shaped API when the schema declaration is intentionally
named `writer`.

## Proposed API

For field-backed FKs, the generated public FK property is the backing field's
Kotlin declaration name:

```kotlin
class Article : EntSchema("articles") {
    val writer = uuid("author_id")
    val author = belongsTo<User>("author").field(writer)
}
```

Generates:

```kotlin
client.articles.create {
    writer = user.id
}.saveOrThrow()

client.articles.update(articleId) {
    writer = otherUser.id
}.saveOrThrow()
```

and hook/candidate surfaces consistently expose `writer`, `unsetWriter()`, and
`ctx.before.writer`.

Implicit FKs are unchanged: `belongsTo<User>("author")` still generates
`authorId`.

## Design

Schema finalization should capture declaration names for fields before codegen
derives FK surfaces. The capture rule is:

- inspect direct Kotlin properties declared on the concrete `EntSchema` class
- find properties whose value is the exact `Field` handle instance used by
  `.field(handle)`
- record the property name as the backing declaration name
- use that captured name as `EdgeFk.propertyName` for field-backed FKs

The physical DB column remains the `Field.name` value. Only generated Kotlin API
names change.

## Diagnostics

Codegen should reject field-backed FK declarations when the backing field's
declaration name cannot be captured unambiguously.

Reject at least:

- no direct property on the concrete schema references the handle
- multiple properties reference the same handle
- the property is delegated, private, inherited, or computed in a way that does
  not expose the stable handle instance

The diagnostic should name the schema, edge, backing column, and the reason the
declaration name could not be captured.

## Acceptance Criteria

- A field-backed FK uses the backing Kotlin `val` name for entity, create,
  update, mutation-view, hook, privacy, validation, and candidate surfaces.
- No synthetic `{edge}Id` alias is generated for field-backed FKs.
- Storage continues to use the backing field's column name.
- Ambiguous or uncapturable backing fields fail during schema/codegen
  validation with an actionable error.
