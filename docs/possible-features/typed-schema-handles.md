# RFC: Typed Schema Handles

## Status

Possible future feature. This is not implemented.

## Summary

Replace string-based schema linkage and registry-style schema assembly
with a Kotlin-first DSL built around:

- delegated property declarations
- inferred logical names from schema properties
- typed field and edge handles for linkage
- domain words like `inverse(...)` instead of string-based `ref(...)`

Today the schema API still relies on string names and repeated labels:

```kotlin
index("author_id")
belongsTo("author", User).field("author_id")
hasMany("posts", Post).ref("author")
manyToMany("groups", Group).through(UserGroup, sourceEdge = "user", targetEdge = "group")
```

This RFC proposes a Kotlin-first shape instead:

```kotlin
object UserSchema : EntSchema<Long>() {
    val name by string().minLen(1).maxLen(64)
    val posts by hasMany(PostSchema)
}

object PostSchema : EntSchema<Long>() {
    val title by string().minLen(1)
    val authorId by long().storageKey("author_id")
    val author by belongsTo(UserSchema)
        .field(authorId)
        .inverse(UserSchema::posts)
        .required()

    override fun indexes() = indexes {
        index(authorId)
    }
}

object FriendshipSchema : EntSchema<Long>() {
    val user by belongsTo(UserSchema).required()
    val friend by belongsTo(UserSchema).required()
}

object UserGraphSchema : EntSchema<Long>() {
    val friends by manyToMany(UserSchema)
        .through(FriendshipSchema, FriendshipSchema::user, FriendshipSchema::friend)
}
```

The goal is to remove typo-prone string linkage and make the schema API
more type-safe, more idiomatic Kotlin, and more self-describing.

## Motivation

The current schema API still has several places where relationships are
linked by string:

- `index("field_name")`
- `belongsTo(...).field("fk_field")`
- `hasMany(...).ref("inverse_edge")`
- `hasOne(...).ref("inverse_edge")`
- `manyToMany(...).through(..., sourceEdge = "x", targetEdge = "y")`

That creates a few recurring problems:

1. Typos are discovered late.
   Many mistakes become codegen-time or migration-time errors instead of
   being rejected immediately by the schema DSL.

2. Refactoring is brittle.
   Renaming a field or edge does not update string references.

3. Physical storage names leak into logical schema APIs.
   With `storageKey(...)`, a logical schema field name and the physical
   column name can differ. Typed handles should refer to the logical
   declaration, not the DB column name.

4. Ambiguous inverse resolution needs too much fallback logic.
   Typed references should make the intended inverse explicit where the
   caller already knows it.

5. The schema reads like a registry instead of declarations.
   Repeating `"author"` in multiple places and re-listing declared
   fields/edges is mechanically workable, but it is not especially
   Kotlin-like.

6. `ref(...)` is framework jargon.
   `inverse(...)` is a better name for the common case because it says
   what the relationship means, not just that some cross-link exists.

## Non-Goals

- Do not use generated entity property references such as
  `Post::authorId`.
- Do not make the runtime depend on generated entities.
- Do not redesign the query API in this RFC.
- Do not preserve string-based linkage as the primary API.
- Do not attempt cross-module compile-time guarantees that Kotlin's type
  system cannot express cleanly.

## Design Goals

- Eliminate string names from linkage-heavy schema APIs.
- Keep schema code readable.
- Infer logical schema names from property declarations where possible.
- Make handles stable under `storageKey(...)`.
- Reject obvious schema mismatches earlier.
- Make property-backed declarations the canonical schema style.
- Prefer domain-language linkage like `inverse(...)` over generic
  string-based `ref(...)`.
- Work naturally with the edge API redesign RFC.

## Proposed API

### Canonical Declaration Style

The canonical schema style should use delegated property declarations.

```kotlin
object PostSchema : EntSchema<Long>() {
    val title by string().minLen(1)
    val authorId by long().storageKey("author_id")
    val author by belongsTo(UserSchema)
        .field(authorId)
        .inverse(UserSchema::posts)
        .required()
}
```

In this model:

- `title`, `authorId`, and `author` are normal Kotlin properties
- the logical schema names come from the property names by default
- `storageKey(...)` remains the escape hatch for physical DB naming

### Field Linkage Uses Field Handles

Field declarations still produce stable typed values that can be used in
linkage-heavy APIs.

```kotlin
val authorId by long().storageKey("author_id")
val createdAt by time()

index(authorId)
index(authorId, createdAt)

belongsTo(UserSchema)
    .field(authorId)
```

### Edge Linkage Uses Typed Inverse References

Inverse relationships should use typed schema references rather than
string names.

```kotlin
val posts by hasMany(PostSchema)
val author by belongsTo(UserSchema)
    .inverse(UserSchema::posts)

val profile by hasOne(ProfileSchema)
    .inverse(ProfileSchema::user)
```

This replaces `.ref("author")` with a typed inverse declaration.

### Many-To-Many Disambiguation

Junction disambiguation should also use handles instead of strings.

```kotlin
val friends by manyToMany(UserSchema)
    .through(FriendshipSchema, FriendshipSchema::user, FriendshipSchema::friend)
```

This replaces:

```kotlin
manyToMany("groups", Group)
    .through(UserGroup, sourceEdge = "user", targetEdge = "group")
```

## Handle Types

The exact generic shape can be tuned, but the core idea is that handles
carry both owner-schema and value/target information, while the public
DSL prefers delegated properties and property-reference linkage.

Illustrative shape:

```kotlin
interface FieldHandle<Owner : EntSchema, T>

interface BelongsToHandle<Owner : EntSchema, Target : EntSchema>
interface HasManyHandle<Owner : EntSchema, Target : EntSchema>
interface HasOneHandle<Owner : EntSchema, Target : EntSchema>
interface ManyToManyHandle<Owner : EntSchema, Target : EntSchema>
```

This enables APIs like:

```kotlin
fun <Owner : EntSchema> index(vararg fields: FieldHandle<Owner, *>)

fun <Owner : EntSchema, Target : EntSchema> BelongsToBuilder<Owner, Target>.field(
    field: FieldHandle<Owner, *>
): BelongsToBuilder<Owner, Target>

fun <Owner : EntSchema, Target : EntSchema> HasManyBuilder<Owner, Target>.inverse(
    inverse: KProperty0<BelongsToHandle<Target, Owner>>
): HasManyBuilder<Owner, Target>

fun <Owner : EntSchema, Target : EntSchema> HasOneBuilder<Owner, Target>.inverse(
    inverse: KProperty0<BelongsToHandle<Target, Owner>>
): HasOneBuilder<Owner, Target>
```

The exact signatures may need variance, a shared `EdgeHandle`
supertype, or eager-handle overloads, but the owner/target relationship
should be part of the model and the Kotlin-facing API should read like
property declarations rather than list assembly.

## Validation Model

Typed handles should move some validation earlier, but not all of it.

### Type-Level Rejection

The API should make these invalid at compile time where possible:

- passing a field handle to `.ref(...)`
- passing an edge handle to `index(...)`
- passing a field from another schema into `.field(...)`
- passing a `hasMany` handle as the inverse of a `hasOne`

### Schema / Codegen Validation

Cross-edge consistency still needs schema-time validation:

- `hasOne.ref(...)` still requires that the inverse `belongsTo` is
  actually `.unique()`
- mismatched bidirectional refs are still an error
- many-to-many self-joins may still need explicit source/target role
  validation

Typed handles reduce ambiguity. They do not remove the need for
structural schema validation.

## Storage Key Semantics

Handles refer to logical schema declarations, not physical column names.

Example:

```kotlin
val authorId = long("author_id").storageKey("author_fk")

belongsTo("author", User).field(authorId)
index(authorId)
```

In this model:

- `authorId` is the logical schema field handle
- `storageKey("author_fk")` only affects generated physical metadata
- callers never need to know whether the DB column is `author_id` or
  `author_fk`

This is a major reason to prefer handles over raw strings.

## Schema Declaration Model

This RFC fits best with a declaration-oriented schema style where fields
and edges are stable properties on the schema object:

```kotlin
object PostSchema : EntSchema {
    val authorId = long("author_id")
    val author = belongsTo("author", User).field(authorId)

    override fun fields() = fields(authorId)
    override fun edges() = edges(author)
}
```

The current list-builder DSL may still be able to support typed handles,
but property-backed declarations are the cleanest fit because they give
handles stable names and identity.

## Initialization Order And Lazy Refs

Typed handles do not reduce the kinds of relationships the schema can
express. They do introduce one important declaration tradeoff:
explicitly cross-referenced schema properties can create initialization
cycles.

Example:

```kotlin
object UserSchema : EntSchema {
    val posts = hasMany("posts", Post).ref { PostSchema.author }
}

object PostSchema : EntSchema {
    val author = belongsTo("author", User).ref { UserSchema.posts }
}
```

Without a lazy form, eager handle-based refs can be awkward or unsafe for
mutually-referential schema declarations.

Recommendation:

- keep typed handles as the linkage model
- support lazy typed refs such as `ref { PostSchema.author }`
- support lazy M2M disambiguation hints such as
  `through(UserGroup, sourceEdge = { UserGroup.user }, targetEdge = { UserGroup.group })`

This keeps the API type-safe without forcing callers to manage schema
initialization order manually.

## Relationship To Edge Schema Redesign

This RFC pairs naturally with [Edge Schema API Redesign](edge-schema-api-redesign.md).

That RFC makes relationship kinds explicit:

- `belongsTo`
- `hasMany`
- `hasOne`
- `manyToMany`

This RFC then makes the references between those declarations typed.

Recommended implementation order:

1. finalize relationship kinds and edge builder types
2. add typed edge handles for `.ref(...)`
3. add typed field handles for `index(...)` and `.field(...)`
4. add typed junction-edge handles for `manyToMany(...).through(...)`

## Compatibility

If this is introduced, the cleanest long-term API is handle-first, not
string-first.

A temporary transition layer could support both:

```kotlin
index("author_id")
index(authorId)
```

But the handle-based API should be the documented primary form, and the
string overloads should be treated as compatibility shims rather than
the canonical design.

## Open Questions

### 1. Should handles be public API objects or opaque internal tokens?

Recommendation:

- public API objects
- opaque implementation
- stable enough to use as DSL linkage values

### 2. Should `indexes { index(...) }` remain variadic by field handle?

Recommendation:

- yes
- `index(authorId, createdAt)` is still readable

### 3. Should typed handles require property-backed declarations?

Recommendation:

- probably yes for the cleanest design
- this is a schema-DSL shape decision, not just a small additive feature

### 4. Should string overloads remain at all?

Recommendation:

- only as a short compatibility layer, if needed
- not as the preferred API

## Acceptance Criteria

This RFC is successful when:

- `index(...)` can use field handles instead of strings
- `belongsTo(...).field(...)` can use a field handle instead of a string
- `hasMany(...).ref(...)` and `hasOne(...).ref(...)` can use typed edge
  handles instead of strings
- `manyToMany(...).through(..., sourceEdge, targetEdge)` can use typed
  edge handles instead of strings
- `storageKey(...)` does not leak physical column names into linkage APIs
- obvious schema-linkage mistakes move from stringly codegen failures to
  earlier type/schema validation
