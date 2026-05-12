# RFC: To-One Assignment And Nullability

## Status

Possible future feature. This is not implemented.

Split out from [Edge Mutation API](00-overview.md).

## Summary

Define the first, smallest edge mutation step: FK-owning `belongsTo` edges can
be written by entity setter method or by id, and relationship nullability becomes
required by default.

This RFC covers:

- `setAuthor(alice)`
- `authorId = aliceId`
- required-by-default `belongsTo(...)`
- `.nullable()`
- field-backed FK nullability matching
- mixed entity-setter/FK writes
- hook, candidate, privacy, and validation visibility for to-one writes

ID-based update roots, many-to-many schema modeling, link-table helpers, and
multi-write transaction semantics are covered by separate RFCs.

This RFC assumes the [ID-Based Update Roots](01-id-based-update-roots.md)
contract. Update roots identify owner rows by id, and generated repos should not
expose `update(entity)` owner-row overloads.

## Motivation

entkt already generates typed edge metadata and eager-loading APIs. Mutation
APIs still tend to expose storage details:

```kotlin
client.posts.create {
    authorId = user.id
}.save()
```

For FK-owning to-one edges, applications should be able to express the
relationship directly:

```kotlin
client.posts.create {
    title = "Hello"
    setAuthor(user)
}.save()
```

The ID-only path should remain available when the caller already has the target
id and does not want to load a full entity.

Examples use `.save()` as shorthand for the current generated save operation. If
the [Result Variants](../entkt-result-variants-rfc.md) RFC is adopted, examples
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

For FK-owning to-one edges, a generated setter method is the ergonomic entity
API:

```kotlin
client.posts.create {
    title = "Hello"
    setAuthor(alice)
}.save()
```

```kotlin
client.posts.update(post.id) {
    setAuthor(bob)
}.save()
```

The same relationship can be written by id when the caller already has the
target id:

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

Passing an entity never requires reloading it; the builder uses the entity id.
Passing an id never loads the target entity automatically. Target existence is
enforced by database constraints unless a validation rule explicitly checks it.
Target LOAD privacy is not evaluated just because a target entity or id appears
in an edge mutation. If an application requires "a caller may assign only targets
they can see", "targets must belong to the same tenant", or similar
relationship-write authorization, it must encode that rule in owner write privacy
or validation. EntKt does not treat target LOAD privacy as relationship-write
authorization.

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
[Schema Nullability Terminology](../schema-nullability-terminology.md) for the
DSL-wide contract.

Nullable to-one edges, declared with `.nullable()`, can be cleared by assigning
`null` through the generated setter method:

```kotlin
client.posts.update(post.id) {
    setAuthor(null)
}.save()
```

For nullable to-one edges, assigning `null` to the resolved FK property also
clears the edge:

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
only when the builder or hooks explicitly set the entity setter method or
resolved FK property to `null`. If final save preparation finds a null FK for a
required relationship that is part of the mutation, save preparation rejects it.

For updates, the patch type of a required FK is `FieldPatch<TargetIdType>`
with a non-nullable `T`, so `FieldPatch.Set(null)` is not a representable
state for required relationships — only `Unset` and `Set(non-null id)` are.
The "explicit `authorId = null` on a required FK" case lives in the
builder/staging layer, not in the patch: it appears as `dirtyFields`
containing the FK property with a `null` underlying value. Generated save
preparation rejects that staging state before the canonical requested
patch is built (and therefore before privacy, validation, or database
writes). At the patch layer, `Unset` means "the update does not touch this
FK" and no required edge check runs for it; reaching the patch with a
`Set(non-null id)` entry has already passed the required check by
construction.

Required edge checks are treated like generated field shape checks: they validate
the local mutation payload and generated schema constraints, not database-visible
domain invariants. Owner privacy and configured validation still run after final
candidate construction.

## Public Types

Generated edge mutators must be typed according to schema nullability.
Required to-one edges must expose non-null entity setter methods and non-null
FK types, such as `setAuthor(user)` and `authorId: UUID`. Nullable to-one edges
must expose nullable entity setter methods and nullable FK types, such as
`setAuthor(null)` and `authorId: UUID?`.

```kotlin
// required relationship
fun setAuthor(value: User)

// nullable relationship
fun setAuthor(value: User?)
```

Generated entity setter method names should be `set` plus the Kotlin schema
declaration property name in UpperCamelCase: `author` becomes `setAuthor(...)`,
and `primaryAuthor` becomes `setPrimaryAuthor(...)`. Generated Kotlin API names
come from the Kotlin declaration name, not the storage/runtime edge string. For
example, `val primaryAuthor = belongsTo<User>("primary_author")` generates
`setPrimaryAuthor(...)` and, for implicit FKs, `primaryAuthorId`. Codegen must
capture and use the Kotlin schema declaration property name for generated
relationship APIs. In V1, declaration property names used for generated
relationship APIs must be valid lowerCamelCase Kotlin identifiers. Codegen
rejects names that require separator or case munging, such as `primary_author`;
callers should use `val primaryAuthor = belongsTo<User>("primary_author")`
instead. Codegen must reject schemas where the generated method name collides
with an existing field, edge, generated method, Kotlin member, or JVM signature.
In V1, callers should rename the declaration property when a method-name
collision would occur.

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
- Its getter has **no side effects** and **does not allocate**: two
  back-to-back reads of the same property must return the same instance
  (`===`). Computed getters that build a fresh `belongsTo<…>(…)` per
  read are rejected.
- The declaration property name is `lowerCamelCase`. Names that would
  require separator munging (e.g. `primary_author`) fail capture; the
  caller renames to a lowerCamelCase declaration (e.g. `primaryAuthor`).

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

Required create builders may use nullable internal staging state to represent
"not assigned yet", but `null` should not be part of the public assignment API
for required edges.

Generated required entity setter methods and required FK setters must
defensively reject null at runtime, even though their Kotlin signatures are
non-null. This protects Java/platform callers and reflective invocation from
leaving required relationships null. Save preparation still performs the final
required FK check as a backstop.

Entity setter methods are write-only commands on mutation builders. The resolved
FK property, such as `authorId`, is the readable/writable source of truth for
pending relationship state.

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

Documentation and examples should present entity setter methods as the ergonomic
to-one API and FK assignment as the ID-only variant. They should not introduce
relationship assignment properties on mutation builders.

Generated entity setter methods and resolved FK properties must include KDoc
explaining the relationship-write semantics. Entity setter methods use only the
target id, do not load the target row, and do not evaluate target LOAD privacy.
Resolved FK properties are the ID-only write path and have the same no-load,
no-target-LOAD-privacy behavior. Relationship-write authorization belongs in
owner write privacy or validation.

## Explicit Backing Fields

Generated create/update builders should expose entity setter methods for
`belongsTo` edges. Setting by entity writes its id into the underlying FK
property:

```kotlin
client.posts.create {
    setAuthor(alice)
}.save()
```

They also expose the resolved FK property, either as the generated implicit FK
such as `authorId`, or as the user-declared field for
`belongsTo(...).field(handle)` edges:

```kotlin
setAuthor(alice)     // sets authorId = alice.id
authorId = alice.id  // writes the FK directly
setAuthor(null)      // .nullable() edge only; clears authorId
authorId = null      // .nullable() edge only; clears the FK directly
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
    setAuthor(alice)      // sets writerId = alice.id
}.save()
```

```kotlin
client.posts.create {
    writerId = alice.id   // writes the FK directly; no authorId is generated
}.save()
```

For implicit FKs, the id-only path is the generated `{edge}Id` property. For
`belongsTo(...).field(handle)` edges, the id-only path is the user-declared
field property backing that edge. The implementation must not create a second
FK path.

For field-backed edges, the entity setter method name is derived from the edge
declaration property name, while the FK property name is the backing field
declaration property name.

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
immutable, create builders may expose the entity setter method and resolved FK
setter, but update builders must not expose either write path for that
relationship. Hook-facing update mutation views also must not expose the
immutable backing FK as mutable. Implicit FK-backed relationships are mutable by
default unless a future edge-level immutability modifier defines otherwise.

Create defaults on field-backed FK fields apply like scalar create defaults
during create final-value computation. Required edge checks see the final
defaulted FK value. Create defaults do not apply to untouched update
relationships. If a backing FK field has an update default, it follows the
ID-based update-root update-default rules and becomes a framework-added effective
patch value. Defaults do not load or validate the target row; target existence
remains enforced by database FK constraints unless a validation rule checks it
earlier.

## Mixed Entity-Setter And FK Writes

Entity setter methods and FK assignment are two public ways to write the same
pending relationship state. If a caller mixes them in one builder, to-one edges
use last-write-wins semantics over the underlying FK value:

```kotlin
authorId = alice.id       // writes authorId
setAuthor(bob)            // writes authorId = bob.id
authorId = carol.id       // writes authorId and clears cached author
setAuthor(null)           // nullable edge only; writes authorId = null
```

The final pending FK value after the create/update block and before hooks have
run is the value hooks initially observe for relationships changed by the
builder. Hooks mutate a hook-facing scalar/FK mutation view, not the public
builder. For to-one relationships, that hook-facing view is FK-only: it exposes
the resolved FK property, such as `authorId`, or the user-declared backing field
for `belongsTo(...).field(handle)` edges. It does not expose relationship entity
setter methods, such as `setAuthor(alice)`, or readable relationship entity
properties. Hooks that need to assign a relationship by entity should write the
entity id into the resolved FK property. Hook FK writes also follow
last-write-wins before candidate construction. Hook, privacy, and validation code
should treat the final pending FK value and `WriteCandidate` as the source of
truth for changed relationship fields, not any cached entity reference that
happened to be assigned earlier in the builder lifecycle.

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
`Unset` and `Set(non-null id)` are representable; an explicit
`authorId = null` on a required FK lives in builder/staging dirty state
and is rejected before the canonical patch is built (see "Relationship
Nullability" above).

Hooks that need current relationship state should read the loaded
update `before` entity or query explicitly when they need data outside
that owner row.

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
mutability, but they do not expose relationship entity setter methods, readable
relationship entity properties, or link-table edge mutators. Create and update
hook interfaces may differ when immutable fields are create-only.

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

## Generated Builder Shape

For each mutable FK-owning to-one edge, generated create/update builders expose
an entity setter method and the resolved FK property. Both write through to the
same pending FK state.

Conceptually, for a required edge:

```kotlin
class PostCreate {
    private var cachedAuthor: User? = null
    private var resolvedAuthorFk: UUID? = null

    fun setAuthor(value: User) {
        cachedAuthor = value
        resolvedAuthorFk = value.id
    }

    var authorId: UUID
        get() = resolvedAuthorFk
            ?: error("authorId has not been assigned")
        set(value) {
            cachedAuthor = null
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

If a caller writes the resolved FK property directly, the generated setter
should clear the cached entity reference because the builder no longer knows
which `User` instance, if any, matches the FK. The entity setter method sets both
the cached entity and the resolved FK. Calling the nullable entity setter with
`null`, or assigning `null` to the resolved FK, clears both for nullable edges.

## Save Pipeline

To-one edge mutations lower to owner-row FK writes. They should be resolved
before candidate construction and do not introduce a second relationship-write
phase for `belongsTo` edges.

Create saves follow the normal generated create pipeline:

1. the create builder block has already run before `save()`, so entity setter
   methods and FK writes have updated pending FK state
2. before hooks run and may mutate hook-facing resolved FK properties
3. generated field defaults and field-backed FK defaults are applied
4. final scalar/FK values are computed, and generated field-shape checks plus
   required edge checks run
5. the full create candidate includes final FK values
6. privacy and validation run in the caller's client scope
7. the owner row is inserted
8. after hooks and returned LOAD privacy run according to the generated write
   pipeline

For updates, the high-level save pipeline is defined by
[ID-Based Update Roots](01-id-based-update-roots.md). This RFC
contributes the to-one-specific steps inside that pipeline. Update-specific
cases such as syntactically empty updates, hook-cleared empty updates, missing
owner rows, update defaults, `NoChanges`, and returned entity hydration are
governed by the ID-Based Update Roots RFC.

- builder `set{Edge}(entity)` calls and FK writes update the requested patch
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
state. Calling `setAuthor(alice)` does not cause the returned `Post` to contain
`alice` in its loaded edge state.

For updates, the returned owner entity must reflect the persisted row after the
update, including untouched scalar and FK fields that were not written. Generated
code may rely on drivers that return a full updated row or perform a follow-up
read when necessary; it must not synthesize untouched values from update input as
if they were persisted state.

```kotlin
val saved = client.posts.update(post.id) {
    setAuthor(alice)
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

1. Document to-one entity setter methods as the ergonomic public API and FK
   assignment as the ID-only variant. Ensure both write through to the same
   resolved FK state.
2. Change relationship nullability to required by default for `belongsTo(...)`,
   with `.nullable()` as the explicit nullable relationship
   marker. Follow the DSL-wide terminology contract from
   [Schema Nullability Terminology](../schema-nullability-terminology.md).
3. Add tests proving required/nullable to-one semantics and hook/privacy/
   validation visibility.

## Test Requirements

Before implementation, add tests for:

- required to-one entity setter method sets the FK
- to-one id assignment sets the FK without loading the target entity
- generated entity setter and resolved FK KDoc documents that to-one writes use
  only target ids, do not load target rows, and do not evaluate target LOAD
  privacy
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
- immutable field-backed relationships can be set on create but cannot be updated
- create defaults on field-backed FKs apply before required edge checks without
  loading or validating the target row
- create defaults on field-backed FKs do not apply to untouched update
  relationships; backing FK update defaults follow ID-based update-root
  update-default rules
- before hooks observe pre-default FK state; field-backed FK defaults apply after
  before hooks during final-value computation
- generated entity setter method names follow `set` plus the Kotlin schema
  declaration property name in UpperCamelCase, not the storage/runtime edge
  string, and reject collisions with fields, edges, generated methods, Kotlin
  members, or JVM signatures
- edge declaration property names whose generated setter method names collide are
  rejected
- schema collection fails if codegen cannot map a registered `belongsTo` builder
  to exactly one stable Kotlin declaration property name
- computed getter edge declarations are rejected when they create new builders
  during property inspection
- implicit FK edges reject generated `{edge}Id` Kotlin member collisions and
  require `belongsTo(...).field(handle)` when callers need an explicit backing
  field name
- field-backed edges derive entity setter names from the edge declaration
  property and FK property names from the backing field declaration property
- field-backed edges check entity setter method names and backing FK property
  names independently for collisions
- nullable to-one `null` assignment clears the FK
- nullable to-one update distinguishes unset from explicit null: unset leaves the
  FK out of the update write set, while explicit null clears it
- nullable to-one create allows unset and explicit null, both producing a null FK
- scalar-only updates do not write untouched FK values back to the database
- unset required to-one create rejects during generated save preparation
- required entity setter methods and required FK setters defensively reject
  Java/platform null calls
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
  `T`); an explicit `authorId = null` on a required FK is caught earlier as
  builder/staging dirty state (dirtyFields + null underlying value) and
  rejected before the canonical requested patch is built — which is also
  before privacy, validation, or database writes
- direct FK writes clear any cached entity reference
- create FK getters follow required-vs-nullable unset behavior: required unset
  throws, while nullable unset returns null
- update FK getters return the pending FK when changed and throw when untouched
- `setAuthor(alice)` does not evaluate target LOAD privacy and does not return
  `alice` as a loaded edge
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
- hook-facing to-one mutation views expose resolved FK fields only, not
  relationship entity setter methods or readable relationship entity properties
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
