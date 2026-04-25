# RFC: Typed Schema Handles

## Status

Possible future feature. This is not implemented.

## Summary

Replace string-based schema linkage and registry-style schema assembly
with a declaration-oriented Kotlin DSL built around:

- plain property declarations
- explicit SQL field and table names
- declaration properties for fields, edges, and indexes
- symbolic schema targets, typed field handles, and property-reference-based edge linkage
- domain words like `inverse(...)` instead of string-based `ref(...)`
- a two-phase model: declare now, resolve/finalize later

Today the schema API still relies on string names and repeated labels:

```kotlin
index("author_id")
belongsTo("author", User).field("author_id")
hasMany("posts", Post).ref("author")
manyToMany("groups", Group).through(UserGroup, sourceEdge = "user", targetEdge = "group")
```

This RFC proposes a Kotlin-first shape instead:

```kotlin
class User : EntSchema<Long>("users") {
    val name = string("name").minLen(1).maxLen(64)
    val posts = hasMany<Post>("posts")
    val friends = manyToMany<User>("friends")
        .through<Friendship>(Friendship::user, Friendship::friend)
}

class Post : EntSchema<Long>("posts") {
    val title = string("title").minLen(1)
    val authorId = long("author_id")
    val author = belongsTo<User>("author")
        .field(authorId)
        .inverse(User::posts)
        .required()
    val byAuthor = index(authorId)
}

class Friendship : EntSchema<Long>("friendships") {
    val user = belongsTo<User>("user").required()
    val friend = belongsTo<User>("friend").required()
}
```

The goal is to replace the current mixed registry/builder style with a
single declaration-oriented schema surface that is:

- explicit about SQL and relationship semantics
- type-safe at linkage points
- idiomatic Kotlin
- easier to read and maintain over time

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
   fields, edges, and indexes is mechanically workable, but it is not
   especially Kotlin-like.

6. `ref(...)` is framework jargon.
   `inverse(...)` is a better name for the common case because it says
   what the relationship means, not just that some cross-link exists.

## Non-Goals

- Do not use generated model/entity property references as schema
  linkage inputs.
- Do not make the runtime depend on generated entities.
- Do not redesign the query API in this RFC.
- Do not preserve string-based linkage as the primary API.
- Do not attempt cross-module compile-time guarantees that Kotlin's type
  system cannot express cleanly.

## Design Goals

- Make schema declarations, not registry methods, the primary API
  surface.
- Eliminate string names from linkage-heavy schema APIs.
- Keep schema code readable.
- Keep SQL column and table naming explicit in schema declarations.
- Avoid schema-level name inference from Kotlin variable names.
- Reject obvious schema mismatches earlier.
- Use plain property declarations as the schema model for fields,
  edges, and indexes.
- Avoid eager cross-schema schema-instance access during declaration.
- Prefer domain-language linkage like `inverse(...)` over generic
  string-based `ref(...)`.
- Work naturally with the edge API redesign RFC.

## Architectural Model

The core design shift in this RFC is not just "typed handles instead of
strings." It is a declaration/finalization split:

1. schema properties declare fields, edges, and indexes
2. schema classes are instantiated and their declarations register with
   the owning schema instance
3. cross-schema targets and cross-schema links are recorded symbolically
4. a later finalize/validate phase resolves the full collected schema graph

Within one codegen/runtime schema graph, each schema class must have
exactly one canonical collected instance. Member references such as
`User::posts` resolve against that canonical instance during
finalization.

In this model, the schema class is the schema's identity and the schema
instance is the container used to materialize declarations. Instances
exist so field, edge, and index declarations have a place to register;
the class is what cross-schema references and graph identity are keyed
on.

Concrete schema classes therefore need to be collector-constructible.
They must be top-level or otherwise outer-instance-free, and they must
be instantiable by the collector without runtime arguments or injected
services. Schema constructors are for declaration materialization only;
constructor side effects outside schema declaration are not part of this
design.

As a result, singleton schema `object`s are not part of this design.
If this RFC is implemented, codegen should fail fast when it encounters
an `object` that extends `EntSchema`.

That means cross-schema declarations should not depend on eagerly
touching other schema instances during declaration.

Instead:

- target schemas are represented symbolically at declaration time
- generic schema targets such as `belongsTo<User>(...)` are captured
  through a reified overload that records a runtime class token such as
  `User::class`
- non-inline builder primitives should accept and store `KClass<Target>`
  values directly, or an equivalent internal schema key derived from
  that class token
- inverse links are recorded, not resolved immediately
- M2M junction links are recorded, not resolved immediately
- schema instances are collected before finalization begins
- finalization performs inverse resolution, M2M role resolution,
  cardinality checks, ordering checks, and fail-fast validation

Concretely, expressions like `inverse(User::posts)` and
`through<Friendship>(Friendship::user, Friendship::friend)` record
symbolic member references only. They do not resolve cross-schema joins
or inverse relationships during property initialization.

This is what makes an eager user-facing API viable without exposing
lazy linkage as the normal shape.

## Proposed API

### Canonical Declaration Style

The canonical schema style should use plain property declarations for
all core schema elements, with explicit SQL field and table names.

```kotlin
class Post : EntSchema<Long>("posts") {
    val title = string("title").minLen(1)
    val authorId = long("author_id")
    val author = belongsTo<User>("author")
        .field(authorId)
        .inverse(User::posts)
        .required()
}
```

In this model:

- the schema class declares its SQL table name explicitly in the
  `EntSchema` constructor
- field declarations name the SQL column up front
- `title`, `authorId`, `author`, and `byAuthor` are plain Kotlin
  properties holding stable schema declarations
- Kotlin property names improve readability, but they are not the
  linkage key and should not drive SQL naming
- no delegated `by` API is required

The intended schema surface is therefore:

- fields are declarations
- edges are declarations
- indexes are declarations

There are no separate registry blocks for those core schema elements.

### Table Names Are Explicit

Table naming should follow the same rule as field naming: the SQL name
is explicit in the declaration, not inferred from the Kotlin class
name.

```kotlin
class User : EntSchema<Long>("users")
class Post : EntSchema<Long>("posts")
```

This RFC does not leave table naming as an implicit convention.
Callers must provide the SQL table name in the schema constructor, and
codegen/runtime should treat that constructor argument as the source of
truth for persistence naming.

### Field Linkage Uses Field Handles

Field declarations still produce stable typed values that can be used in
linkage-heavy APIs.

```kotlin
val authorId = long("author_id")
val createdAt = time("created_at")

index(authorId)
index(authorId, createdAt)

belongsTo<User>("author")
    .field(authorId)
```

Because the SQL name is already explicit, field linkage should never
need to repeat raw strings like `"author_id"`.

### Edge Linkage Uses Typed Inverse References

Inverse relationships should use typed schema references rather than
string names.

```kotlin
val posts = hasMany<Post>("posts")
val author = belongsTo<User>("author")
    .inverse(User::posts)
```

This replaces `.ref("author")` with a typed inverse declaration.

The important distinction is that `inverse(User::posts)` records a
symbolic member reference. It does not eagerly resolve the inverse
during property initialization. Resolution happens later during schema
finalization.

### Many-To-Many Disambiguation

Junction disambiguation should also use typed member references instead
of strings.

```kotlin
val friends = manyToMany<User>("friends")
    .through<Friendship>(Friendship::user, Friendship::friend)
```

This replaces:

```kotlin
manyToMany("groups", Group)
    .through(UserGroup, sourceEdge = "user", targetEdge = "group")
```

As with `inverse(...)`, `through(...)` records symbolic junction-edge
member references that are validated during schema finalization rather
than eagerly resolved during declaration.

## Handle Types

The exact generic shape can be tuned, but the core idea is that field
and declaration handles carry value/target information in the public
type surface, while owner identity is tracked internally. The public
DSL prefers plain property declarations, field handles, and
property-reference-based edge linkage.

The public examples in this RFC intentionally use the clean user-facing
shape `EntSchema<Long>(...)`. If implementations need internal owner
keys or hidden typing helpers to enforce declaration ownership, that is
an implementation detail, not the intended surface syntax.

In this RFC, builder objects are also the public declaration handles.
That is an intentional simplification:

- `belongsTo(...)`, `hasMany(...)`, `hasOne(...)`, and `manyToMany(...)`
  return mutable declaration objects
- those same objects are what schema properties store and what other
  declarations reference
- declarations register with the owning schema when they are created
- declarations remain configurable during schema construction
- declarations are frozen before finalization, validation, codegen, or
  migration planning runs

So this RFC does not introduce a second public layer of "temporary
builders" and "final handles." The fluent declaration object is the
registered handle.

Illustrative declaration-handle shape:

```kotlin
interface SchemaKey

interface DeclarationHandle {
    val ownerKey: SchemaKey
}

interface FieldHandle<T> : DeclarationHandle
interface IndexHandle : DeclarationHandle

interface BelongsToHandle<Target : EntSchema<*>> : DeclarationHandle
interface HasManyHandle<Target : EntSchema<*>> : DeclarationHandle
interface HasOneHandle<Target : EntSchema<*>> : DeclarationHandle
interface ManyToManyHandle<Target : EntSchema<*>> : DeclarationHandle
```

One possible internal typing sketch looks like this:

```kotlin
abstract class EntSchema<ID : Any>(val tableName: String) {
    internal val schemaKey: SchemaKey

    protected fun index(
        vararg fields: FieldHandle<*>
    ): IndexHandle

    protected inline fun <reified Target : EntSchema<*>> belongsTo(
        name: String,
    ): BelongsToBuilder<Target> = belongsTo(name, Target::class)

    protected fun <Target : EntSchema<*>> belongsTo(
        name: String,
        target: KClass<Target>,
    ): BelongsToBuilder<Target>

    protected inline fun <reified Target : EntSchema<*>> hasMany(
        name: String,
    ): HasManyBuilder<Target> = hasMany(name, Target::class)

    protected fun <Target : EntSchema<*>> hasMany(
        name: String,
        target: KClass<Target>,
    ): HasManyBuilder<Target>

    protected inline fun <reified Target : EntSchema<*>> hasOne(
        name: String,
    ): HasOneBuilder<Target> = hasOne(name, Target::class)

    protected fun <Target : EntSchema<*>> hasOne(
        name: String,
        target: KClass<Target>,
    ): HasOneBuilder<Target>

    protected inline fun <reified Target : EntSchema<*>> manyToMany(
        name: String,
    ): ManyToManyBuilder<Target> = manyToMany(name, Target::class)

    protected fun <Target : EntSchema<*>> manyToMany(
        name: String,
        target: KClass<Target>,
    ): ManyToManyBuilder<Target>
}

fun <Target : EntSchema<*>>
BelongsToBuilder<Target>.field(
    field: FieldHandle<*>
): BelongsToBuilder<Target>

fun <Target : EntSchema<*>>
BelongsToBuilder<Target>.inverse(
    inverse: KProperty1<Target, HasManyHandle<*>>
): BelongsToBuilder<Target>

fun <Target : EntSchema<*>>
BelongsToBuilder<Target>.inverse(
    inverse: KProperty1<Target, HasOneHandle<*>>
): BelongsToBuilder<Target>

fun <Target : EntSchema<*>, Junction : EntSchema<*>> ManyToManyBuilder<Target>.through(
    sourceEdge: KProperty1<Junction, BelongsToHandle<*>>,
    targetEdge: KProperty1<Junction, BelongsToHandle<Target>>,
): ManyToManyBuilder<Target>
```

The exact internal signatures may need variance, a shared `EdgeHandle`
supertype, or a different owner-key representation. The public DSL
target remains the plain declaration style shown above, not a
self-typed `EntSchema<Self, ID>` surface. Because the public API keeps
`EntSchema<ID>`, same-schema owner checks and FK value-type checks are
schema/codegen validation responsibilities rather than hard compile-time
guarantees.

## Validation Model

Typed handles should move some validation earlier, but not all of it.

### Type-Level Rejection

The API should make these invalid at compile time where possible:

- passing a field handle to `.inverse(...)`
- passing an edge handle to `index(...)`
- passing a property reference of the wrong declaration kind into
  `belongsTo(...).inverse(...)`
- passing a property reference of the wrong declaration kind into
  `manyToMany(...).through(...)`

### Schema / Codegen Validation

The clean public API intentionally does not use a self-typed schema base,
so some correctness checks are schema/codegen responsibilities rather
than Kotlin compile-time guarantees. These checks must run during schema
finalization before generated code, migrations, or runtime metadata are
emitted:

- `.field(handle)` and `index(handle)` must reject handles declared by a
  different collected schema instance
- `.field(handle)` must reject fields whose value type does not match
  the target schema's ID type
- `belongsTo(...).inverse(Target::edge)` must reject inverse edges that
  do not target the owning schema
- `belongsTo(...).inverse(Target::hasOneEdge)` still requires that the
  `belongsTo` edge is actually `.unique()`
- a backing field reused via `.field(handle)` must not silently change
  the edge's cardinality; conflicting field uniqueness vs edge
  uniqueness is a schema error
- mismatched bidirectional refs are still an error
- `manyToMany(...).through<Junction>(sourceEdge, targetEdge)` must
  reject refs that are not declared by the junction schema
- `through(...)` must reject a source edge that does not target the
  owning schema
- `through(...)` must reject a target edge that does not target the
  many-to-many target schema
- `through(...)` must reject source and target refs that resolve to the
  same junction edge

Typed handles reduce ambiguity. They do not remove the need for
structural schema validation.

## Explicit SQL Naming

This RFC should keep persistence naming SQL-first. The explicit string
passed to a schema constructor is the physical table name, and the
explicit string passed to a field builder is the physical column name.
Those declaration sites are the source of truth for storage naming.

Example:

```kotlin
class Post : EntSchema<Long>("posts") {
    val authorId = long("author_id")

    val author = belongsTo<User>("author").field(authorId)
    val byAuthor = index(authorId)
}
```

In this model:

- `"posts"` is the actual SQL table name
- `authorId` is a typed handle to the declared field
- `"author_id"` is the actual SQL column name
- linkage uses the handle, not the string
- schema authors do not rely on class-name-based table inference
- schema authors do not rely on camelCase -> snake_case inference from
  Kotlin property names

Because the field declaration already names the SQL column explicitly,
and the schema constructor already names the SQL table explicitly,
public `storageKey(...)`-style persistence overrides are unnecessary in
this design.

## Schema Declaration Model

This RFC fits best with a declaration-oriented schema style where
fields, edges, and indexes are stable properties on the schema type:

```kotlin
class Post : EntSchema<Long>("posts") {
    val authorId = long("author_id")
    val author = belongsTo<User>("author").field(authorId)
    val byAuthor = index(authorId)
}
```

In this design, schema declarations are discovered from the schema's
plain properties rather than re-registered through `fields()` /
`edges()` / `indexes()` methods.

That is an intentional simplification:

- plain property declarations are the canonical schema style
- `fields()` / `edges()` / `indexes()` registry methods are not part of
  this design
- property-backed declarations give handles stable names and identity

## Declaration Discovery

Schema declarations should be discovered by builder registration, not by
reflecting over schema properties.

Recommended rule:

- a schema registry/collector is responsible for constructing schema
  instances before finalization
- the registry/collector must construct exactly one canonical instance
  per schema class in the graph
- concrete schema classes must be collector-constructible:
  top-level or static-nested, not `inner`, and constructible without
  runtime parameters
- each field builder registers its handle with the owning schema during
  schema instance construction
- each edge builder registers its handle with the owning schema during
  schema instance construction
- each index declaration registers with the owning schema during schema
  instance construction
- codegen, metadata builders, and migration planning read those
  registered declarations from the collected schema instances
- plain properties are the authoring model; registration is the
  implementation model
- registered declaration objects are frozen after schema collection and
  before finalization begins
- schema `object`s are invalid input and should cause codegen to fail
- constructor logic outside declaration materialization is unsupported;
  the collector must run constructors to discover declarations, so
  arbitrary side effects cannot be reliably detected or prevented before
  they occur

Reflection is not the source of truth for discovery, and reflection
order must not define declaration order.

This also means only values created by schema builder APIs participate
in discovery. Arbitrary helper properties on the schema class or schema
instance do not.

## Declaration Ordering

Field, edge, and index order must remain explicit and deterministic.

This matters because declaration order currently feeds:

- generated constructor and property order
- emitted schema metadata order
- emitted index metadata order
- migration planning and diff stability
- snapshot and codegen test determinism

In this design, order should be defined by registration order during
schema instance construction, not by reflection order.

Recommended rule:

- each field/edge/index declaration registers with the owning schema
  during schema instance construction
- the schema preserves those declarations in declaration order
- codegen, metadata emission, and migration planning preserve that order

This avoids making declaration order an implementation detail of Kotlin
reflection while still allowing plain property declarations to be the
source of truth.

## Initialization Order And Finalization

Typed handles do not reduce the kinds of relationships the schema can
express. The important implementation constraint is that cross-schema
links must be recorded symbolically during declaration and resolved only
after schema instances have been constructed and collected.

Example:

```kotlin
class User : EntSchema<Long>("users") {
    val posts = hasMany<Post>("posts")
}

class Post : EntSchema<Long>("posts") {
    val author = belongsTo<User>("author").inverse(User::posts)
}
```

In this model:

- `hasMany<Post>("posts")` records `Post` as a symbolic target
- `inverse(User::posts)` records a symbolic inverse member reference
- neither declaration resolves the cross-schema graph eagerly
- finalization binds `User::posts` against the one canonical collected
  `User` instance
- finalization resolves and validates the full graph after schema
  instances have been collected

Recommendation:

- keep eager typed handles as the public linkage model
- use unbound property references on schema classes for cross-schema
  inverse and M2M linkage
- make finalization responsible for inverse resolution, M2M role
  resolution, and cross-schema validation
- do not expose lazy linkage closures as the normal public API shape

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
3. add property-reference-based inverse linkage
4. add property-reference-based junction linkage for
   `manyToMany(...).through(...)`

## Breaking Change

This RFC intentionally removes string-based linkage APIs.

The design target is handle-first only:

- `index(authorId)`
- `belongsTo<User>("author").field(authorId)`
- `belongsTo<User>("author").inverse(User::posts)`
- `manyToMany<Group>("groups").through<UserGroup>(UserGroup::user, UserGroup::group)`

The old string-based forms are not part of this design target:

- `index("author_id")`
- `.field("author_id")`
- `.ref("inverse_edge")`
- `through(..., sourceEdge = "user", targetEdge = "group")`

If this RFC is implemented, those older linkage forms should be removed
rather than preserved through compatibility shims.

## Open Questions

### 1. What stable identity semantics should public declaration handles expose?

Recommendation:

- declaration handles are public API objects
- handle identity should be reference-based within a collected schema
  graph
- the RFC does not require value-based equality across distinct schema
  graphs or separately constructed schema instances

### 2. Should the DSL also support delegated `by` property syntax?

Recommendation:

- not in the first version
- plain property declarations are the design baseline
- delegated `by` syntax can be reconsidered later as a pure ergonomic
  layer, not as a competing schema model

### 3. How should backing-field uniqueness interact with edge cardinality?

Recommendation:

- relationship cardinality is declared on the edge
- backing-field uniqueness is storage metadata only
- a unique backing field does not implicitly upgrade `belongsTo(...)` to
  one-to-one
- if a unique backing field is reused by a non-unique `belongsTo(...)`,
  that is a conflicting schema and should be rejected

Example:

```kotlin
val ownerId = long("owner_id").unique()
val owner = belongsTo<User>("owner").field(ownerId)
```

This should fail with a clear error because the field declares a unique
FK column while the edge still declares non-unique `belongsTo(...)`
semantics.

The explicit valid form is:

```kotlin
val ownerId = long("owner_id").unique()
val owner = belongsTo<User>("owner")
    .unique()
    .field(ownerId)
```

This keeps the model explicit:

- field metadata controls storage
- edge metadata controls relationship semantics
- when they disagree, the schema fails early instead of inferring or
  silently rewriting relationship shape

## Acceptance Criteria

This RFC is successful when:

- `index(...)` can use field handles instead of strings
- `belongsTo(...).field(...)` can use a field handle instead of a string
- `belongsTo(...).field(...)` rejects fields whose value type does not
  match the target schema's ID type
- `belongsTo(...).inverse(...)` can use property references instead of
  strings
- `manyToMany(...).through(..., sourceEdge, targetEdge)` can use
  property references instead of strings
- explicit field declarations remain the source of truth for SQL column
  names
- explicit schema constructor arguments remain the source of truth for
  SQL table names
- obvious schema-linkage mistakes move from stringly codegen failures to
  earlier type/schema validation
