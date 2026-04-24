# RFC: Typed Schema Handles

## Status

Possible future feature. This is not implemented.

## Summary

Replace string-based schema linkage and registry-style schema assembly
with a Kotlin-first DSL built around:

- plain property declarations
- explicit SQL field names
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
    val name = string("name").minLen(1).maxLen(64)
    val posts = hasMany("posts", PostSchema)
}

object PostSchema : EntSchema<Long>() {
    val title = string("title").minLen(1)
    val authorId = long("author_id")
    val author = belongsTo("author", UserSchema)
        .field(authorId)
        .inverse(UserSchema.posts)
        .required()

    override fun indexes() = indexes {
        index(authorId)
    }
}

object FriendshipSchema : EntSchema<Long>() {
    val user = belongsTo("user", UserSchema).required()
    val friend = belongsTo("friend", UserSchema).required()
}

object UserGraphSchema : EntSchema<Long>() {
    val friends = manyToMany("friends", UserSchema)
        .through(FriendshipSchema, FriendshipSchema.user, FriendshipSchema.friend)
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

3. SQL field names are repeated across linkage APIs.
   Even when the schema declares `long("author_id")`, callers still end
   up repeating `"author_id"` in `index(...)` or `.field(...)` instead
   of reusing the declaration directly.

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
- Keep SQL column naming explicit in field declarations.
- Avoid schema-level name inference from Kotlin variable names.
- Reject obvious schema mismatches earlier.
- Make property-backed declarations the canonical schema style.
- Prefer domain-language linkage like `inverse(...)` over generic
  string-based `ref(...)`.
- Work naturally with the edge API redesign RFC.

## Proposed API

### Canonical Declaration Style

The canonical schema style should use plain property declarations with
explicit SQL field names.

```kotlin
object PostSchema : EntSchema<Long>() {
    val title = string("title").minLen(1)
    val authorId = long("author_id")
    val author = belongsTo("author", UserSchema)
        .field(authorId)
        .inverse(UserSchema.posts)
        .required()
}
```

In this model:

- field declarations name the SQL column up front
- `title`, `authorId`, and `author` are plain Kotlin properties holding
  stable schema handles
- Kotlin property names improve readability, but they are not the
  linkage key and should not drive SQL naming
- no delegated `by` API is required

### Field Linkage Uses Field Handles

Field declarations still produce stable typed values that can be used in
linkage-heavy APIs.

```kotlin
val authorId = long("author_id")
val createdAt = time("created_at")

index(authorId)
index(authorId, createdAt)

belongsTo("author", UserSchema)
    .field(authorId)
```

Because the SQL name is already explicit, field linkage should never
need to repeat raw strings like `"author_id"`.

### Edge Linkage Uses Typed Inverse References

Inverse relationships should use typed schema references rather than
string names.

```kotlin
val posts = hasMany("posts", PostSchema)
val author = belongsTo("author", UserSchema)
    .inverse(UserSchema.posts)

val profile = hasOne("profile", ProfileSchema)
    .inverse(ProfileSchema.user)
```

This replaces `.ref("author")` with a typed inverse declaration.

### Many-To-Many Disambiguation

Junction disambiguation should also use handles instead of strings.

```kotlin
val friends = manyToMany("friends", UserSchema)
    .through(FriendshipSchema, FriendshipSchema.user, FriendshipSchema.friend)
```

This replaces:

```kotlin
manyToMany("groups", Group)
    .through(UserGroup, sourceEdge = "user", targetEdge = "group")
```

## Handle Types

The exact generic shape can be tuned, but the core idea is that handles
carry both owner-schema and value/target information, while the public
DSL prefers plain property declarations and handle-based linkage.

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
    inverse: BelongsToHandle<Target, Owner>
): HasManyBuilder<Owner, Target>

fun <Owner : EntSchema, Target : EntSchema> HasOneBuilder<Owner, Target>.inverse(
    inverse: BelongsToHandle<Target, Owner>
): HasOneBuilder<Owner, Target>
```

The exact signatures may need variance, a shared `EdgeHandle`
supertype, or lazy-handle overloads, but the owner/target relationship
should be part of the model and the Kotlin-facing API should read like
plain declarations rather than list assembly.

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

## Explicit SQL Naming

This RFC should keep field declarations SQL-first. The explicit string
passed to a field builder is the physical column name and the source of
truth for storage naming.

Example:

```kotlin
val authorId = long("author_id")

belongsTo("author", User).field(authorId)
index(authorId)
```

In this model:

- `authorId` is a typed handle to the declared field
- `"author_id"` is the actual SQL column name
- linkage uses the handle, not the string
- schema authors do not rely on camelCase -> snake_case inference from
  Kotlin property names

Because the field declaration already names the SQL column explicitly,
public field `storageKey(...)` is unnecessary in this design.

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
    val posts = hasMany("posts", Post).inverse { PostSchema.author }
}

object PostSchema : EntSchema {
    val author = belongsTo("author", User).inverse { UserSchema.posts }
}
```

Without a lazy form, eager handle-based refs can be awkward or unsafe for
mutually-referential schema declarations.

Recommendation:

- keep typed handles as the linkage model
- support lazy typed refs such as `inverse { PostSchema.author }`
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
2. add typed field handles for `index(...)` and `.field(...)`
3. add typed edge handles for inverse linkage
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
- plain property declarations, not delegated `by` properties
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
- explicit field declarations remain the source of truth for SQL column
  names
- obvious schema-linkage mistakes move from stringly codegen failures to
  earlier type/schema validation
