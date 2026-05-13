# RFC: To-One FK Mutation And Nullability

## Status

Possible future feature. This is not implemented.

Split out from [Edge Mutation API](00-overview.md).

## Summary

FK-owning `belongsTo` edges are mutated only through their resolved FK
property. The generated API does not expose relationship entity setter
methods such as `setAuthor(user)` or relationship assignment properties
such as `author = user`. Relationship nullability is required by default
and nullable only with `.nullable()`.

This RFC covers:

- `authorId = aliceId`
- required-by-default `belongsTo(...)`
- `.nullable()`
- field-backed FK nullability matching
- hook, candidate, privacy, and validation visibility for to-one writes

ID-based update roots, many-to-many schema modeling, link-table helpers, and
multi-write transaction semantics are covered by separate RFCs.

This RFC assumes the [ID-Based Update Roots](01-id-based-update-roots.md)
contract. Update roots identify owner rows by id, and generated repos should not
expose `update(entity)` owner-row overloads.

## Motivation

entkt already generates typed edge metadata and eager-loading APIs. To-one
mutation APIs should make the write path explicit: generated writes operate on
owner-row FK values, not loaded target entities.

```kotlin
client.posts.create {
    title = "Hello"
    authorId = user.id
}.save()
```

This keeps create and update semantics aligned with ID-based update roots:
mutation builders accept scalar values and FK ids. Loaded entity objects remain
read/query state, not mutation input.

Examples use `.save()` as shorthand for the current generated save operation. If
the [Result Variants](../tooling/entkt-result-variants-rfc.md) RFC is adopted, examples
should use `saveOrThrow()` for the throwing path or `saveOrError()` for
structured errors. `saveOrNull()` must not swallow privacy, validation,
constraint, transaction, or driver failures.

## Non-Goals

- Do not hide required edge validation.
- Do not bypass privacy or validation.
- Do not add cascading create for nested objects.
- Do not mutate inverse edges (`hasOne` / `hasMany`) from the non-FK-owning side
  in this RFC. Those relationships should be changed from the `belongsTo` side
  that owns the FK.
- Do not define many-to-many mutation helpers here.

## Proposed API

For FK-owning to-one edges, the generated FK property is the public write API:

```kotlin
client.posts.create {
    title = "Hello"
    authorId = alice.id
}.save()
```

```kotlin
client.posts.update(post.id) {
    authorId = bob.id
}.save()
```

The caller may also write an id value directly when it already has one:

```kotlin
client.posts.create {
    title = "Hello"
    authorId = aliceId
}.save()
```

```kotlin
client.posts.update(post.id) {
    authorId = bobId
}.save()
```

Writing a target id never loads the target entity automatically. Target existence
is enforced by database constraints unless a validation rule explicitly checks
it. Target LOAD privacy is not evaluated just because a target id appears in an
edge mutation. If an application requires "a caller may assign only targets they
can see", "targets must belong to the same tenant", or similar relationship-write
authorization, it must encode that rule in owner write privacy or validation.
EntKt does not treat target LOAD privacy as relationship-write authorization.

If the database rejects the FK because the target id does not exist, the
throwing path surfaces a constraint exception and `saveOrError()` surfaces
`EntError.ConstraintViolation`, not `ValidationFailed`, unless a custom
validation rule checked target existence earlier. (`EntError.ConstraintViolation`
is defined by the [Result Variants RFC](../tooling/entkt-result-variants-rfc.md);
this RFC depends on that variant landing for `saveOrError()` to surface it.
Until then, database constraint exceptions propagate as their underlying
exception types from `saveOrError()`.)

## Relationship Nullability

The relationship nullability model is required by default. A
`belongsTo<Target>(...)` edge produces a non-null FK unless the schema marks the
relationship with `.nullable()`. `.nullable()` is the only relationship
nullability modifier — see
[Schema Nullability Terminology](../schema/schema-nullability-terminology.md) for the
DSL-wide contract.

Nullable to-one edges, declared with `.nullable()`, can be cleared by assigning
`null` through the resolved FK property:

```kotlin
client.posts.update(post.id) {
    authorId = null
}.save()
```

Required edge checks should continue to happen during generated save preparation
before privacy, validation, or database writes. For create, a required
relationship must have a final non-null FK after builder and hook writes. For
update, untouched relationships are not part of the mutation: they are not
required to be reassigned, are not marked dirty, are not included in the update
patch as changed FK values, and are not written back. The full after-state
candidate uses the current owner row loaded by the update root as its fallback
base. Required edge checks apply to create-time required FKs and to update FKs
explicitly written by the builder or hooks. A nullable relationship is cleared
only when one of these explicit writes happens: the builder assigns `null` to the
resolved FK property (`authorId = null`), or a hook assigns `null` to the FK
property on the hook-facing mutation view (`ctx.mutation.authorId = null`). If
final save preparation finds a null FK for a required relationship that is part
of the mutation, save preparation rejects it.

For updates, the patch type of a required FK is `FieldPatch<TargetIdType>`
with a non-nullable `T`, so `FieldPatch.Set(null)` is not a representable
state for required relationships — only `Unset` and `Set(non-null id)` are.
Normal write paths cannot produce a dirty+null required FK either:
the generated required FK setter rejects null at entry (see "Public
Types"), and both DSL callers and `beforeUpdate` hooks go through that
setter — DSL callers via `update(id) { authorId = ... }`, hooks via
`ctx.mutation.authorId = ...`. There is no other public surface that
writes to the FK's backing state. At the patch layer, `Unset` means
"the update does not touch this FK" and no required edge check runs
for it; a `Set(non-null id)` entry has already passed the entry
check by construction.

Generated save preparation's `_checkRequiredNotNull()` runs as a
backstop only for paths that bypass the setter entirely — for example
reflection that writes the backing field directly, or a future
internal/generated bulk-write helper that doesn't re-enter the
property setter. Those paths are not part of the public API; the
backstop is an internal corruption guard, not a check on user-facing
mutation forms.

Required edge checks are treated like generated field shape checks: they validate
the local mutation payload and generated schema constraints, not database-visible
domain invariants. Owner privacy and configured validation still run after final
candidate construction.

## Public Types

Generated to-one FK properties must be typed according to schema nullability.
Required to-one edges must expose non-null FK types, such as `authorId: UUID`.
Nullable to-one edges must expose nullable FK types, such as `authorId: UUID?`.

```kotlin
// required relationship
var authorId: UUID

// nullable relationship
var authorId: UUID?
```

Implicit FK property names use the Kotlin schema declaration property name plus
`Id`: `author` becomes `authorId`, and `primaryAuthor` becomes
`primaryAuthorId`. Generated Kotlin API names come from the Kotlin declaration
name, not the storage/runtime edge string. For example,
`val primaryAuthor = belongsTo<User>("primary_author")` generates
`primaryAuthorId` as the implicit FK property. Codegen must capture and use the
Kotlin schema declaration property name for implicit FK APIs. In V1, declaration
property names used for generated relationship APIs must be valid lowerCamelCase
Kotlin identifiers. Codegen rejects names that require separator or case munging,
such as `primary_author`; callers should use
`val primaryAuthor = belongsTo<User>("primary_author")` instead. Codegen must
reject schemas where the generated FK property name collides with an existing
field, edge, generated method, Kotlin member, or JVM signature. In V1, callers
should rename the declaration property or use `belongsTo(...).field(handle)` when
a collision would occur.

## Declaration Property Name Capture

Generated Kotlin API names are derived from the Kotlin declaration property
name. Schema collection must map each registered edge builder to **exactly
one** Kotlin member property declared on the schema class instance being
inspected.

### Eligible property shape

A property is eligible to name a registered edge builder only when **all**
of the following hold:

- It is a public, non-`private`, non-`protected` Kotlin `val` declared
  directly on the schema class (or `object`) instance — not inherited as
  an abstract member from a superclass and not introduced by an
  interface default.
- It has no property delegate (`by lazy`, `by Delegates.observable`, or
  any custom `getValue` provider). Delegated reads can return new
  instances per access and cannot guarantee identity stability with the
  self-registered builder.
- Two back-to-back reads of the property return the same builder
  instance (`===`). A computed getter that builds a fresh
  `belongsTo<…>(…)` per read fails this check because the second read
  produces a different instance.
- Reading the property does not register additional edge builders on
  the schema. Schema inspection counts registered declarations before
  and after the read; a getter that calls `belongsTo<…>(…)` (or any
  other edge-registering helper) during inspection raises the count
  and is rejected.
- The declaration property name is `lowerCamelCase`. Names that would
  require separator munging (e.g. `primary_author`) fail capture; the
  caller renames to a lowerCamelCase declaration (e.g. `primaryAuthor`).

Schema inspection enforces eligibility through these *observable*
invariants — identity stability across reads, non-mutation of the
registered declaration count, and the structural checks above
(public/non-delegated/declared-directly/lowerCamelCase). Arbitrary
side effects in a getter that don't disturb those invariants (e.g.
logging, incrementing a counter unrelated to declaration registration)
are not detectable from inspection and remain unsupported behavior:
they may produce surprising results but won't be diagnosed at schema
validation time.

### Mapping rules

- Each registered edge builder must be reachable through **exactly one**
  eligible declaration property on the schema instance. If two eligible
  properties resolve to the same registered builder via identity (`===`),
  schema validation fails — the second `val` is treated as an alias and
  is not allowed.
- No two registered declarations may map to the same property name.
  Shadowing through inheritance or interface defaults is rejected at
  schema validation time, not silently resolved.
- A registered builder that is **not** reachable via any eligible
  property (e.g. it lives in a private field, a delegated property, or
  an inherited abstract slot) fails capture and the caller is directed
  to rewrite the declaration as a normal
  `val edgeName = belongsTo<Target>("edge_name")` form.

### Diagnostics

When capture fails, schema validation emits a diagnostic naming both
the offending property (or the orphaned builder) and the rule that
rejected it:

- *"Edge builder at … is reachable only through a private/delegated/
  inherited member; declare it as a public val on the schema class."*
- *"Edge builder at … is reachable through both `<A>` and `<B>`; a
  registered builder must map to exactly one property."*
- *"Property `<name>` on `<Schema>` returns a freshly-allocated builder
  on each read; declare it as `val <name> = belongsTo<…>(…)`."*
- *"Property name `<name>` requires separator munging; rename the
  declaration to lowerCamelCase."*

### Field-backed FK declaration capture

For `belongsTo(...).field(handle)` edges, schema inspection must also
map the backing field builder to exactly one eligible Kotlin declaration
property — the field participates in generated FK API names and the
caller's declared name is authoritative.

The same eligibility and mapping rules from "Eligible property shape"
and "Mapping rules" above apply to the backing field property:

- It is a public, non-`private`, non-`protected` Kotlin `val`
  declared directly on the schema class.
- Two back-to-back reads return the same field builder instance
  (`===`).
- Reading the property does not register additional field or edge
  builders on the schema.
- It is not delegated (`by lazy`, custom `getValue`, etc.).
- The declaration property name is `lowerCamelCase`.
- It maps to exactly one registered field builder via identity; the
  field builder cannot be aliased through a second `val`.

The generated FK property name is the backing field declaration
property name — *not* the storage column name and *not* a
synthesized `{edge}Id` suffix. For example, `val writer = uuid("writer_id")`
produces a generated FK property named `writer`, and
`val writerId = uuid("writer_id")` produces a generated FK property
named `writerId`. See the "Explicit Backing Fields" section for the
worked examples.

If capture fails (the field is delegated, private, inherited, aliased
through another `val`, or its property name requires separator munging),
schema validation emits the same family of diagnostics as for edge
builders, directing the caller to declare the field as a plain
`val fieldName = uuid("column_name")` on the schema class.

### Scope

This RFC requires declaration-name capture for **edge** API generation
(implicit FK property names) and for **field-backed FK** API generation (the
user-declared field that backs a `belongsTo(...).field(handle)` edge). It does
not redefine the naming model for scalar field properties in general.

The current convention aligns Kotlin declaration names with storage
column names (e.g. `val writerId = uuid("writer_id")`), and the
implementation continues to derive generated scalar property names
that way. A broader change to derive all scalar generated property
names from Kotlin declaration names regardless of the storage string
is out of scope here and belongs in a separate schema-naming RFC if
ever adopted.

Required create builders may use nullable internal staging state to represent
"not assigned yet", but `null` should not be part of the public assignment API
for required edges.

Generated required FK setters must defensively reject null **at setter entry**,
even though their Kotlin signatures are non-null. The check fires before any
state is mutated; it does not rely on save preparation to catch the null. This
protects Java/platform callers and reflective invocation that can bypass
Kotlin's non-null type contract.

```kotlin
// Required relationship — rejects null at the call site.
override var authorId: UUID
    get() = /* throw-on-untouched */
    set(value) {
        @Suppress("SENSELESS_COMPARISON")  // Kotlin sees value as non-null
        requireNotNull(value) { "authorId is required" }
        field = value
        dirtyFields.add("authorId")
    }
```

Save preparation's `_checkRequiredNotNull()` remains as a final
backstop for paths that mutate builder state without re-entering
these setters. Such paths are internal — they are not part of the
hook API — and include things like reflection that writes the
backing field directly (skipping the property setter), or a future
generated bulk-write helper that bypasses the per-property entry
checks. The hook-facing `${Entity}UpdateMutationView` does not expose
`dirtyFields` or any other way to mutate state outside the generated FK setter
and `unset{FkProperty}()`, so hooks always go through the entry check. For normal
call sites — Kotlin, Java, or reflection through the property setter — the setter
rejects before the value reaches the builder's internal state; the backstop only
fires for paths that skip the property surface entirely.

The resolved FK property, such as `authorId`, is the public readable/writable
source of truth for pending relationship state.

Resolved FK getter behavior should be explicit:

- on create builders for required relationships, reading the FK before assignment
  must throw because there is no valid FK value yet
- on create builders for nullable relationships, reading the FK before
  assignment returns `null`
- on update builders for required relationships, reading the FK returns the
  pending FK if the relationship was changed; reading an untouched FK must throw
  because update builders do not have current-state values before `save()`
- on update builders for nullable relationships, reading the FK returns the
  pending non-null FK or explicit pending null if the relationship was changed;
  reading an untouched FK must throw because `null` is a valid clear value and
  cannot also represent untouched current state on the builder
- writing the FK always marks that relationship pending/dirty

Documentation and examples should present FK assignment as the to-one mutation
API. They should not introduce relationship entity setter methods or relationship
assignment properties on mutation builders.

Concretely, after this RFC implements, the only relationship-write form that
compiles on a generated mutation builder is the resolved FK property. Entity
object assignment APIs are not generated:

```kotlin
// required relationship — this compiles
authorId = alice.id

// nullable relationship — this compiles
authorId = null

// removed by this RFC — must not compile on the generated builder
author = alice
author = null
setAuthor(alice)
setAuthor(null)
```

There is no readable `author` property on the public mutation builder
either: relationship entity reads are not part of the public surface,
only the FK property (`authorId`) is readable. Hooks observe pending relationship
state through `ctx.patch.authorId` and current state through
`ctx.before.authorId` — the internal byId load returns the owner row with
unloaded edges, so the target entity is not available on `ctx.before` itself;
hooks that need the target row must query it explicitly via
`ctx.client.users.byId(ctx.before.authorId)`. The hook-facing mutation view is
FK-only, exposing `authorId = ...` (and `unsetAuthorId()` per the patch
contract). Hooks assign relationships by writing the target id into the FK
property directly: `ctx.mutation.authorId = alice.id`.

Generated resolved FK properties must include KDoc explaining the
relationship-write semantics. FK properties write only target ids, do not load
the target row, and do not evaluate target LOAD privacy. Relationship-write
authorization belongs in owner write privacy or validation.

## Explicit Backing Fields

Generated create/update builders expose the resolved FK property for `belongsTo`
edges. For implicit FKs, this is the generated `{edge}Id` property:

```kotlin
client.posts.create {
    authorId = alice.id
}.save()
```

For nullable implicit FKs, assigning `null` clears the relationship:

```kotlin
authorId = null      // .nullable() edge only; clears the FK
```

For a field-backed edge, the user-declared field is the id-only path:

```kotlin
class Post : EntSchema("posts") {
    val writerId = uuid("writer_id")
    val author = belongsTo<User>("author").field(writerId)
}
```

```kotlin
client.posts.create {
    writerId = alice.id   // no authorId is generated
}.save()
```

For implicit FKs, the id-only path is the generated `{edge}Id` property. For
`belongsTo(...).field(handle)` edges, the id-only path is the user-declared
field property backing that edge. The implementation must not create a second
FK path.

For field-backed edges, the FK property name is the backing field declaration
property name. The backing field name is authoritative — it is **not** a
synthesized `{edge}Id` suffix. If the backing field is named without an `Id`
suffix, the generated FK property has no `Id` suffix either:

```kotlin
class Post : EntSchema("posts") {
    val writer = uuid("writer_id")                                // backing field name has no Id suffix
    val author = belongsTo<User>("author").field(writer)
}
```

Generates:

```kotlin
client.posts.update(post.id) {
    writer = alice.id     // FK property — name from the backing field declaration ("writer")
}.save()
```

Not `writerId = alice.id` and not `authorId = alice.id`. There is no
synthesized `Id`-suffixed FK property for a field-backed edge.

In V1, a declared backing field may back at most one
`belongsTo(...).field(handle)` edge. Codegen rejects schemas where multiple
`belongsTo` edges reuse the same field handle. Alias relationships over the same
FK require a separate future design.

For field-backed edges, hook-facing mutation views, write candidates, privacy,
and validation expose the user-declared backing field, such as `writerId`. They
do not expose a generated `authorId` property or relationship entity property
for that edge.

For implicit FK edges, codegen must reject schemas where the generated FK
property name, such as `authorId`, collides with an existing field, edge,
generated method, or Kotlin member name. Callers should use
`belongsTo(...).field(handle)` to choose an explicit backing field when a
collision would occur.

For `belongsTo(...).field(handle)` edges, relationship nullability and backing
field nullability must match. Codegen should reject a required relationship
backed by a nullable field, and should reject a nullable relationship backed by
a non-null field. The edge declaration and field declaration should describe the
same database constraint instead of one side overriding the other.

The backing field type must also match the target schema id type. For example, a
`belongsTo<User>` whose target id is `UUID` may be backed only by a UUID field
with matching nullability. Codegen should reject mismatched backing field types.

For field-backed relationships, relationship uniqueness is declared on the
`belongsTo(...).unique()` edge modifier. A backing field may also carry
`.unique()` only if the edge is also `.unique()`; codegen normalizes this to one
unique FK constraint. Codegen rejects a unique backing field used by a non-unique
`belongsTo(...).field(handle)` edge, because scalar field uniqueness must not
silently upgrade relationship cardinality.
Relationship cardinality is read from the edge declaration, not inferred from
the backing field, so generated edge metadata and database constraints must
agree.

The backing field also controls relationship mutability. If the backing field is
immutable, create builders may expose the resolved FK setter, but update builders
must not expose a write path for that relationship. Hook-facing update mutation
views also must not expose the immutable backing FK as mutable. Implicit
FK-backed relationships are mutable by default unless a future edge-level
immutability modifier defines otherwise.

Create defaults on field-backed FK fields apply like scalar create defaults
during create final-value computation. Required edge checks see the final
defaulted FK value.

For nullable field-backed FKs, the rule is **explicit null wins**.
The FK property name comes from the backing field declaration (e.g.
`writer` for `val writer = uuid("writer_id")`, `writerId` for
`val writerId = uuid("writer_id")`), not from the implicit `{edge}Id`
pattern — see "Explicit Backing Fields" above for the naming rule:

- If the caller does not touch the relationship (unset), the create
  default fires and the persisted FK is the default value.
- If the caller explicitly assigns `null` to the backing FK property
  (e.g. `writerId = null` or `writer = null`, whichever the schema
  declared), the default is suppressed and the persisted FK is `null`.
  The explicit assignment is treated as the caller's intentional
  choice.

This rule is relationship-specific in this RFC. Whether scalar
fields with create defaults follow the same explicit-null-wins
contract is not defined here and is not assumed; that would require
nullable scalar create builders to distinguish "untouched" from
"explicitly assigned `null`" via assigned-flag tracking, which is
outside this RFC's scope.

This requires the create builder to distinguish "untouched" from
"explicitly assigned `null`" for nullable field-backed FKs — typically via the
same dirty-tracking shape the update path uses (an internal "assigned" flag set
by the FK property setter, separate from the underlying value).

Required field-backed FKs cannot accept an explicit `null` assignment
(the setters defensively reject null at entry — see Public Types), so
"explicit null vs unset" does not arise for them; unset triggers the
default and required edge checks see the defaulted value.

Create defaults do not apply to untouched update relationships.
**Update defaults are not allowed on relationship FKs.** The generic
ID-based update-default rule applies framework defaults to every
non-empty update, which would silently rewrite an untouched
relationship — for example, `update(post.id) { title = "x" }` would
add `writerId` to the effective patch via its update default and
change the relationship even though the caller did not touch it.
That conflicts with the rule that untouched relationships stay
absent from the update patch and are not written back.

Concretely:

- For **field-backed** edges (`belongsTo(...).field(handle)`), codegen
  rejects an `updateDefault(...)` modifier on the user-declared
  backing field. Schema validation emits a diagnostic directing the
  caller to express the intent as a `beforeUpdate` or `afterUpdate`
  hook on the owner entity instead.
- For **implicit FK** edges, there is no user-declared field surface
  where `updateDefault(...)` could be attached — implicit FKs are
  synthesized by codegen, not declared by the schema author — so the
  feature simply does not exist for them. No rejection is needed at
  the schema level; there is no DSL form to reject.

Defaults do not load or validate the target row; target existence
remains enforced by database FK constraints unless a validation rule
checks it earlier.

## FK Writes And Hook Visibility

FK assignment is the only public to-one relationship write path. Multiple writes
to the same FK property in one builder use ordinary last-write-wins semantics:

```kotlin
authorId = alice.id       // writes authorId
authorId = bob.id         // overwrites authorId
authorId = carol.id       // final value before hooks
```

The final pending FK value after the create/update block and before hooks have
run is the value hooks initially observe for relationships changed by the
builder. Hooks mutate a hook-facing scalar/FK mutation view, not the public
builder. For to-one relationships, that hook-facing view is FK-only: it exposes
the resolved FK property, such as `authorId`, or the user-declared backing field
for `belongsTo(...).field(handle)` edges. It does not expose readable
relationship entity properties; relationship entity setter methods are not
part of the generated API at all. Hooks assign a relationship by writing
the target id into the resolved FK property. Hook FK writes also
follow last-write-wins before candidate construction. Hook, privacy,
and validation code should treat the final pending FK value and `WriteCandidate`
as the source of truth for changed relationship fields.

Hook-facing to-one mutation views are **value-oriented**: a resolved FK
getter returns the pending value when one exists and throws when the
relationship is untouched. `beforeUpdate` hooks receive the update hook
context defined by [ID-Based Update Roots](01-id-based-update-roots.md),
which includes the loaded `before` entity for current-database-state
reads.

The **intent classification** — untouched vs explicit `null` vs explicit
non-null value — lives in `ctx.patch`, not in the mutation getter. For a
nullable FK, `ctx.patch.authorId` is one of:

- `FieldPatch.Unset` — the relationship was not touched.
- `FieldPatch.Set(null)` — the relationship was explicitly cleared.
- `FieldPatch.Set(id)` — the relationship was set to a non-null target.

A hook that needs to distinguish "the caller didn't touch authorId"
from "the caller explicitly set authorId to null" should read
`ctx.patch.authorId`, not `ctx.mutation.authorId`. The mutation getter
collapses the first case into a thrown `IllegalStateException` and the
second into a `null` return — useful for value-style code that wants a
nullable Kotlin value with throw-on-untouched as a fail-fast, but not a
substitute for the patch as an intent API.

For update hook-facing mutation views, each mutable relationship FK
also exposes `unset{FkProperty}()` according to the ID-based update-root
patch contract. Calling `unsetAuthorId()` removes the relationship FK
from the requested patch (`FieldPatch.Unset`). Setting `authorId = null`
on a nullable relationship means `FieldPatch.Set(null)` and clears the
relationship; it does not unset the patch entry.

For required FKs the patch type is `FieldPatch<TargetIdType>` and only
`Unset` and `Set(non-null id)` are representable. Normal DSL and hook
writes can't produce a dirty+null required FK either: the generated
required FK setter rejects null at entry, and both `update(id) { authorId = ... }`
and `ctx.mutation.authorId = ...` go through that setter.
`_checkRequiredNotNull()` runs as an internal backstop only for
setter-bypassing paths (reflection writing the backing field, a
future internal bulk-write helper); it is not a check on user-facing
mutation forms. See "Relationship Nullability" above for the full
treatment.

Hooks that need the current FK value should read it from the loaded
`before` row (`ctx.before.authorId`). The relationship edges on
`ctx.before` are unloaded, so the target row itself is not directly
accessible; hooks that need the target entity must query it
explicitly via `ctx.client.users.byId(ctx.before.authorId)`.

Hook-facing resolved FK properties are typed according to relationship
nullability. Required relationship FKs expose non-null setters and defensively
reject Java/platform nulls; nullable relationship FKs expose nullable setters.
Required create hooks may set an unset required FK through the non-null setter,
but cannot intentionally set it to null. Final save preparation still rejects
required create FKs and changed required update FKs that remain unset/null after
hooks.

Hook-facing create interfaces use the same resolved FK getter behavior as create
builders: reading an unset required FK must throw, while reading an unset
nullable FK returns null.

Hooks may clear nullable relationships by setting the hook-facing resolved FK
property to null. Hooks may not leave required relationships null: required edge
checks run after hooks and reject a final null FK for create-time required FKs or
changed required update FKs before privacy, validation, or database writes.

### Hook-Facing API Shape

Codegen should generate hook-facing mutation interfaces separately from the
public create/update builders. Hook callbacks receive these restricted
interfaces, not the concrete public builders. The interfaces expose mutable
scalar fields and resolved FK fields according to field and relationship
mutability, but they do not expose relationship entity properties or link-table
edge mutators. Create and update hook interfaces may differ when immutable fields
are create-only.

`beforeSave` receives a common restricted `{Entity}Mutation` interface shared by
create and update hooks. That common interface exposes only fields and FKs that
are writable in both create and update hook contexts. Create-only values,
including immutable fields and immutable field-backed FKs, are not exposed on
`beforeSave`; use `beforeCreate` for those. `beforeCreate` receives a restricted
create hook interface, and `beforeUpdate` receives the update hook context
defined by [ID-Based Update Roots](01-id-based-update-roots.md), whose
`mutation` property is the restricted update hook interface.

On the common `beforeSave` mutation interface, update-side getters follow update
patch semantics. Reading an unset update scalar or FK field throws rather than
returning current database state. Hook authors who need current owner state in an
update should use the `beforeUpdate` context's loaded `before` entity.

`beforeSave` hooks must be written against the **shared setter-only**
pattern: they should *write* field/FK values (e.g. `m.updatedAt = Instant.now()`)
but should not *read* them. A read like `m.title` works for create (the
create builder's getter returns the staged value or `null`) but throws
on update when the field is untouched — and a generic `beforeSave`
hook has no way to tell which phase it's running in.

The RFC does not include a phase tag on the `beforeSave` mutation
interface. Hooks that genuinely need different behavior for create
vs update should split into a `beforeCreate` hook (richer create
builder, value-style reads work) and a `beforeUpdate` hook (full
`UpdateHookContext` with `before`, `patch`, and restricted `mutation`
view). Keeping `beforeSave` write-only is the simplest contract: it
is the right place for cross-cutting writes like timestamp injection,
denormalized counters, and other set-and-forget mutations that apply
uniformly to both create and update.

## Generated Builder Shape

For each mutable FK-owning to-one edge, generated create/update builders expose
the resolved FK property. That property is the only relationship write path.

Conceptually, for a required edge:

```kotlin
class PostCreate {
    private var resolvedAuthorFk: UUID? = null

    var authorId: UUID
        get() = resolvedAuthorFk
            ?: error("authorId has not been assigned")
        set(value) {
            resolvedAuthorFk = value
        }
}
```

This is conceptual, not a required implementation shape. Generated code may
store only one backing FK field as long as assignment and dirty tracking behave
the same way.

The nullable private fields in this conceptual required-edge example are staging
state only. They let create builders distinguish an unset required edge from an
assigned edge before save preparation. They do not mean public assignment accepts
`null` for required relationships.

Generated builders must distinguish three pending states for nullable
relationships: unset, set to a non-null FK, and explicitly set to null. For
create, unset and explicitly set null both persist null unless defaults are added
later. For update, unset means leave the FK out of the update write set;
explicitly set null means clear the FK. Required relationships only need unset
vs non-null.

Generated builders do not cache assigned target entity objects for relationship
writes. The builder stores only the pending FK value and the dirty/assigned state
needed to distinguish unset from explicit null for nullable relationships.

## Save Pipeline

To-one edge mutations lower to owner-row FK writes. They should be resolved
before candidate construction and do not introduce a second relationship-write
phase for `belongsTo` edges.

Create saves follow the normal generated create pipeline:

1. the create builder block has already run before `save()`, so FK writes have
   updated pending FK state
2. before hooks run and may mutate hook-facing resolved FK properties
3. generated field defaults and field-backed FK defaults are applied
4. final scalar/FK values are computed, and generated field-shape checks plus
   required edge checks run
5. the full create candidate includes final FK values
6. privacy runs in the caller's client scope (keeps the caller's
   privacy context); validation runs with the System-scoped
   LOAD-privacy bypass so validator reads through `ctx.client` are
   not filtered by the caller's LOAD privacy (see "Candidate And
   Rule Visibility" below for the shared rule)
7. the owner row is inserted
8. after hooks and returned LOAD privacy run according to the generated write
   pipeline

For updates, the high-level save pipeline is defined by
[ID-Based Update Roots](01-id-based-update-roots.md). This RFC
contributes the to-one-specific steps inside that pipeline. Update-specific
cases such as syntactically empty updates, hook-cleared empty updates, missing
owner rows, update defaults, `NoChanges`, and returned entity hydration are
governed by the ID-Based Update Roots RFC.

- builder FK writes update the requested patch
- untouched relationship FKs remain absent from the requested patch
- hook-facing FK writes update the requested patch
- nullable FK `null` writes become `FieldPatch.Set(null)`, not unset
- required edge checks run on changed required FKs in the effective patch
- the full after-state candidate is built by applying the effective patch to the
  loaded `before` row

Before hooks observe the pending FK state before generated field defaults are
applied. Field-backed FK defaults are applied during final-value computation
after before hooks and before required edge checks. Hooks that need to derive
from defaulted values should use a future structured mutation phase, not V1
before hooks.

## Returned Entity State

A to-one mutation returns the owner entity with scalar fields and FK fields
reflecting the saved owner row. Relationship edges remain in the normal unloaded
state. Writing `authorId = alice.id` does not cause the returned `Post` to
contain `alice` in its loaded edge state.

For updates, the returned owner entity must reflect the persisted row after the
update, including untouched scalar and FK fields that were not written. Generated
code may rely on drivers that return a full updated row or perform a follow-up
read when necessary; it must not synthesize untouched values from update input as
if they were persisted state.

```kotlin
val saved = client.posts.update(post.id) {
    authorId = alice.id
}.save()

saved.authorId == alice.id
// The returned entity has the updated FK, but its relationship edge state is unloaded.
```

## Candidate And Rule Visibility

Create candidates remain full write candidates. Under ID-based update roots,
update builders and hooks produce a requested update patch. The requested patch
contains only fields and FKs explicitly changed by the builder or hooks.
Untouched update relationships are not marked dirty and should not appear as
changed FK patch values.

Update privacy and validation contexts should receive the loaded update `before`
row, the requested update patch, the effective update patch, and a full
after-state candidate built by applying the effective patch to the loaded row.
To-one edge mutations need no additional candidate model because they lower to FK
values inside the create candidate, requested update patch, effective update
patch, and after-state candidate.

Privacy contexts keep the caller's privacy context. Validation contexts keep the
existing System-scoped LOAD-privacy bypass. Transaction-scoped context behavior
is covered in [Transaction And Locking Semantics](04-transaction-locking-semantics.md).

## Rollout Plan

1. Document FK assignment as the public to-one mutation API.
2. Change relationship nullability to required by default for `belongsTo(...)`,
   with `.nullable()` as the explicit nullable relationship
   marker. Follow the DSL-wide terminology contract from
   [Schema Nullability Terminology](../schema/schema-nullability-terminology.md).
3. Add tests proving required/nullable to-one semantics and hook/privacy/
   validation visibility.

## Test Requirements

Before implementation, add tests for:

- to-one id assignment sets the FK without loading the target entity
- generated resolved FK KDoc documents that to-one writes use only target ids, do
  not load target rows, and do not evaluate target LOAD privacy
- `belongsTo(...)` is required/non-null by default, while `.nullable()` makes a
  to-one edge nullable
- `belongsTo(...).field(handle)` rejects mismatches between relationship
  nullability and backing field nullability
- `belongsTo(...).field(handle)` rejects backing field types that do not match
  the target schema id type
- `belongsTo(...).field(handle)` rejects multiple edges that reuse the same
  backing field
- field-backed relationship uniqueness is declared on
  `belongsTo(...).unique()`; a unique backing field is allowed only when the edge
  is also unique
- codegen rejects unique backing fields used by non-unique
  `belongsTo(...).field(handle)` edges
- field-backed relationships inherit backing field immutability
- immutable field-backed relationships can set their FK on create but cannot be
  updated
- create defaults on field-backed FKs apply before required edge checks without
  loading or validating the target row
- create defaults on field-backed FKs do not apply to untouched update
  relationships
- codegen rejects `updateDefault(...)` on a field used as a
  `belongsTo(...).field(handle)` backing FK — schema validation
  emits a diagnostic directing the caller to express the intent as
  a hook on the owner entity
- implicit FK relationships do not support update defaults at all
  (no user-declared field surface exists where the modifier could be
  attached); no schema rejection is required because there is no DSL
  form to express it
- before hooks observe pre-default FK state; field-backed FK defaults apply after
  before hooks during final-value computation
- generated implicit FK property names use the Kotlin schema declaration property
  name plus `Id`, not the storage/runtime edge string, and reject collisions with
  fields, edges, generated methods, Kotlin members, or JVM signatures
- the generated update/create builder exposes the resolved FK property
  (`authorId`) for relationship writes, and does **not** expose `setAuthor(...)`
  or a writable `author` property — codegen assertion: the generated
  `${Entity}Update` / `${Entity}Create` class contains `var authorId:` and does
  not contain `public fun setAuthor(`/`public fun set<Edge>(` or `var author:`
  for any `belongsTo` edge
- the old property-style relationship assignment `author = alice` /
  `author = null` does not compile against the generated builder —
  compile-fail assertion: a Kotlin source snippet that writes
  `client.posts.update(id) { author = alice }` fails to type-check with
  an "unresolved reference: author" error, while `authorId = alice.id`
  type-checks on the same builder
- the generated builder does not expose a readable relationship entity
  property — codegen assertion: no `public val author:` or
  `public var author:` declaration appears in the generated
  `${Entity}Update` / `${Entity}Create` for any `belongsTo` edge
- the hook-facing `${Entity}UpdateMutationView` interface exposes the FK setter
  (`authorId`) and `unsetAuthorId()`, but no `setAuthor` member
- edge declaration property names whose generated implicit FK names collide are
  rejected
- schema collection fails if codegen cannot map a registered `belongsTo` builder
  to exactly one stable Kotlin declaration property name
- computed getter edge declarations are rejected when they create new builders
  during property inspection
- implicit FK edges reject generated `{edge}Id` Kotlin member collisions and
  require `belongsTo(...).field(handle)` when callers need an explicit backing
  field name
- field-backed edges derive FK property names from the backing field declaration
  property
- field-backed edges check backing FK property names for collisions
- nullable to-one `null` assignment clears the FK
- nullable to-one update distinguishes unset from explicit null: unset leaves the
  FK out of the update write set, while explicit null clears it
- nullable to-one create, when no create default applies to the backing FK,
  allows unset and explicit null and both produce a null persisted FK
- nullable field-backed FK create with a default applied: unset triggers
  the default (persisted FK is the default value) while explicit null
  suppresses the default (persisted FK is null) — explicit-null-wins
- scalar-only updates do not write untouched FK values back to the database
- unset required to-one create rejects during generated save preparation
- required FK setters defensively reject Java/platform null calls
- hooks can set a required FK that the builder left unset before required edge
  validation runs
- hooks can set nullable FK values through the hook-facing resolved FK property
- hooks can clear a nullable FK by setting it to null
- update hooks can remove a pending to-one FK patch entry with
  `unset{FkProperty}()`, while assigning `null` to a nullable FK remains
  `FieldPatch.Set(null)` and clears the relationship
- required FKs left unset/null after hooks are rejected before privacy,
  validation, or database writes
- update required edge checks inspect only effective patch entries; required
  FK `FieldPatch.Unset` skips the check (the update does not touch the FK)
  and a `Set(non-null id)` entry has already passed the check by
  construction. The `FieldPatch.Set(null)` state is not representable for
  required FKs (patch type is `FieldPatch<TargetIdType>` with non-nullable
  `T`). Normal write paths reject null at the FK setter entry, so neither
  DSL callers nor `beforeUpdate` hooks can put the builder into a
  dirty+null state. `_checkRequiredNotNull()` runs as an internal
  backstop only for paths that bypass the setter (reflection writing
  the backing field, a future internal bulk-write helper) and fires
  before the canonical requested patch is built — which is also
  before privacy, validation, or database writes
- create FK getters follow required-vs-nullable unset behavior: required unset
  throws, while nullable unset returns null
- update FK getters return the pending FK when changed and throw when untouched
- writing `authorId = alice.id` does not evaluate target LOAD privacy and does
  not return `alice` as a loaded edge
- returned update entities reflect the persisted row, not synthesized update
  input values
- missing target FK writes surface database constraint errors; under
  `saveOrError()` they surface as `EntError.ConstraintViolation` once the
  [Result Variants RFC](../tooling/entkt-result-variants-rfc.md) defines
  that variant and generated `saveOrError()` catches constraint exceptions
  (deferred to that RFC; until then the underlying exception propagates)
- hooks observe final pending FK values after builder writes and can mutate
  changed FK values through the hook-facing scalar/FK mutation view
- update hooks throw when reading untouched relationship FKs instead of exposing
  current database state
- hook-facing to-one mutation views expose resolved FK fields only, not readable
  relationship entity properties
- hook callbacks receive restricted hook-facing mutation interfaces, not the
  concrete public create/update builders
- `beforeSave` receives a common restricted mutation interface that excludes
  create-only immutable fields and immutable field-backed FKs
- update-side getters on the common `beforeSave` mutation interface throw for
  unset update scalar/FK fields instead of returning current database state
- reading an unset required FK from a create hook-facing mutation view throws,
  while setting it before required edge validation succeeds
- field-backed to-one edges expose the user-declared backing field in hooks,
  candidates, privacy, and validation, without a synthetic `{edge}Id` alias
- update requested/effective patches expose only changed FK values
  (`FieldPatch.Set` / `FieldPatch.Unset` per FK), not the full owner row
- create candidates and update after-state candidates both expose full
  FK values for every field (update after-state is built by folding the
  effective patch over the loaded `before`)
- to-one update candidates do not require target entity loads
