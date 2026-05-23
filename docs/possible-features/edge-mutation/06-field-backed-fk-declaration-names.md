# RFC: Field-Backed FK Declaration Names

## Status

Possible future feature. This is not implemented.

Extracted from the implemented
[To-One FK Mutation And Nullability](../../implemented-features/edge-mutation/02-to-one-assignment-nullability.md)
RFC.

**Dependency: [07-generated-member-name-collisions.md](../../implemented-features/edge-mutation/07-generated-member-name-collisions.md)
must land first.** Declaration-name capture changes generated
member names — `authorId` becomes `writer` when the backing field's
declaration name diverges from `toCamelCase(column)`. That can
introduce new collisions with other generated members on the same
entity (e.g. an edge named `writer` or a sibling field also named
`writer`). RFC 07 specifies how those collisions are detected and
diagnosed; without it, this RFC's renames can silently produce
duplicate-property compile errors instead of an actionable
diagnostic at schema validation time.

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

`EntSchema.finalize()` captures declaration names by reflecting
over the concrete schema class's direct public Kotlin properties.
For each property whose getter returns a `FieldBuilder` instance,
the property's name is recorded on the corresponding built `Field`
as a `declarationName` slot. Codegen reads
`backingField.declarationName` when deriving the generated FK API
name for `belongsTo(...).field(handle)` edges.

The physical DB column remains the `Field.name` value. Only
generated Kotlin API names change.

This approach is preferred over "look up the handle instance at
codegen time" because:

- The declaration name lives on the `Field` model itself, so
  every codegen pass (entity, create, update, patch, candidate,
  hook, privacy, validation) reads it the same way.
- Capture happens once at finalize time, not repeatedly per
  edge during codegen.
- Reflection runs only against the schema's direct properties,
  not its inheritance tree or delegated members, which keeps the
  capture rule narrow and the failure modes (item below) tight.

### V1 Capture Scope

Only **direct public `val` properties on the concrete schema
class** whose declared type is a `FieldBuilder` qualify for
declaration-name capture. Specifically excluded in V1:

| Excluded property shape | Why excluded |
|---|---|
| `private val ...` / non-public properties | Reflection has to elevate access; the declaration name is not part of the public schema contract |
| Inherited from a superclass (`open class BaseSchema { val createdAt = time(...) }`) | The declaration is on the parent, not the concrete class; cross-class capture isn't worth the complexity in V1 |
| Delegated (`val x by lazy { ... }`, `val x by SomeDelegate`) | The Kotlin property's getter does not return the underlying `FieldBuilder` instance directly; capture rule can't reliably resolve them |
| Computed getters (`val x get() = time("...")`) | Each invocation produces a fresh `FieldBuilder`; the captured "property → builder" association is unstable across invocations |
| Mixin-backed (`val createdAt = timestampsMixin.createdAt`) | The declaration lives on the mixin; the host schema's property is a re-export, not the original declaration site |

When a `belongsTo(...).field(handle)` uses a backing handle
whose property shape is in the excluded list, schema validation
**rejects** the schema with an actionable diagnostic — codegen
does not silently fall back to `toCamelCase(field.name)`. The
"reject" stance is intentional: silent fallback would let the
generated API name diverge from what a reader expects based on
the schema's declaration, and the difference would only surface
when a later refactor accidentally shifts a property out of
the qualifying set. A loud rejection forces the schema author
to either restructure the declaration into a qualifying shape
or, if a use case for one of the excluded shapes emerges,
motivate a follow-up RFC that extends V1's capture scope.

Non-FK-backed fields (plain field declarations on the schema
that are not referenced from any `belongsTo(...).field(handle)`)
are unaffected — they continue to use their column name for
storage and `toCamelCase(field.name)` for any non-FK generated
API. The V1 capture scope governs only what shapes are allowed
to be the backing field of a field-backed FK.

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

### Generated-API surfaces

For a field-backed FK whose backing property is named `writer`
and whose column is `author_id`:

- **Entity class** exposes `writer: UUID` (not `authorId: UUID`).
- **Create builder** exposes `writer: UUID?` as the FK setter.
- **Update builder** exposes `writer: UUID?` as the FK setter and
  `unsetWriter()` as the nullable-clear helper.
- **`UpdatePatch` / `UpdateContext.requestedPatch` / `effectivePatch`**
  carry a `writer` slot, not `authorId`.
- **`WriteCandidate`** exposes `writer`, so privacy/validation
  rules read `candidate.writer` to inspect the FK.
- **Hook contexts** (`CreateHookContext`, `UpdateHookContext`,
  `BeforeDelete` / `AfterDelete` receivers) expose `before.writer`
  / `candidate.writer` consistently.
- **Privacy and validation rule scopes** match the same name.
- No synthetic `{edge}Id` alias is generated alongside the
  declaration name (no `authorId` shadow property on entity or
  builders for this FK).

### Storage invariance

- The persisted column name remains `author_id`. The driver row
  map continues to carry the column name; the entity decoder
  bridges to `writer` via the captured `declarationName`.

### Capture rules

- `EntSchema.finalize()` populates `Field.declarationName` for
  every direct public `val` property of type `FieldBuilder` on
  the concrete schema class.
- **Computed-getter properties don't create declarations during
  capture.** A `val x get() = time("...")` produces a fresh
  `FieldBuilder` per invocation and is skipped by the capture
  pass — `declarationName` remains null on any `Field` produced
  from such a getter. Codegen then fails the rejection check
  below if that `Field` is referenced from `belongsTo(...).field(handle)`.
- **Mixin-backed property re-exports don't capture.** Including
  a mixin (e.g. `val ts = include(::Timestamps)`) does not
  contribute to the host schema's declaration map; the
  `Field.declarationName` reflects the mixin's own property name
  if any, not the host's.

### Diagnostics

- A schema with a `belongsTo(...).field(handle)` whose backing
  handle's `declarationName` is null (uncaptured) fails
  `validateEntSchemas` with a diagnostic naming the schema,
  edge, backing column, and the excluded-shape category.
- A schema in which two direct properties reference the same
  `FieldBuilder` instance (`val a = uuid("x"); val b = a`) fails
  `validateEntSchemas` with a duplicate-property diagnostic
  naming both property paths.
- Diagnostics are surfaced during schema validation, not at
  codegen emission time, so the user sees the error during the
  schema-author iteration loop (not buried in a downstream
  Kotlin compile error from a clashing generated member).
