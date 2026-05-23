# RFC: Generated Member Name Collisions

## Status

**V1 implemented.** The per-(schema, artifact) manifest + collision check ships
in `:codegen` (`GeneratedMember.kt`, `BuildMemberManifest.kt`,
`MemberCollisionDiagnostic.kt`), wired into both `SchemaInspector.validate`
(collects diagnostics into the result's errors list) and `EntGenerator.generate`
(throws on first collision so direct codegen callers can't bypass the check).
V1 covers the 7 artifacts listed in §"V1 artifact coverage" below; Phase 2
artifacts are deferred per §"Phase 2 (deferred)".

Extracted from the implemented
[To-One FK Mutation And Nullability](02-to-one-assignment-nullability.md)
RFC.

## Summary

Codegen should validate all generated member names that share a Kotlin artifact
namespace, not just generated property names. The current validation catches the
highest-frequency field/edge/FK property collisions, but it does not fully cover
generated helper methods such as `unset{Property}()` or hook-facing view
members.

This RFC is the general generated-name validation pass. The
[Field-Backed FK Declaration Names](06-field-backed-fk-declaration-names.md)
RFC must either depend on this RFC or implement the smaller subset it needs:
field-backed FK property names and their generated `unset{Property}()` methods
must be validated before codegen emits the new declaration-derived names.

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

Field-backed FK declaration-name capture adds another collision source:

```kotlin
val writer = uuid("author_id")
val author = belongsTo<User>("author").field(writer)
val unsetWriter = bool("unset_writer")
```

If the field-backed FK API becomes `writer`, update hooks generate
`unsetWriter()`. The schema should fail during validation with a diagnostic that
names the generated artifact and both sources.

## Design

Do not validate one global namespace. Validate generated Kotlin source names per
generated artifact, because some names are intentionally repeated across
artifacts while others share a single Kotlin member namespace. Storage names,
such as DB column names and table names, are not part of this RFC.

Codegen should build generated-member manifests per schema and per artifact. A
manifest entry should record:

- the generated artifact, such as `Post`, `PostEdges`, `PostCreate`,
  `PostUpdate`, `PostMutation`, `PostCreateMutationView`,
  `PostUpdateMutationView`, `PostCreatePatch`, `PostUpdatePatch`,
  `PostCreateCandidate`, `PostUpdateCandidate`, `PostCreatePrivacyContext`,
  `PostUpdateValidationContext`, or `PostPendingEdgeOps`
- the member name
- the member kind, such as property, function, nested type, or constructor
  parameter
- the schema declaration that caused it

Validation should reject duplicate member names within each artifact namespace.
It should also reject schema declarations that collide with fixed framework
members on those artifacts.

The manifest should be built after schema finalization and after all generated
names are derived. FK names depend on resolved edge metadata and, for
field-backed FKs, on backing-field metadata, so field registration is too early
to run this validation completely.

Conceptually:

```kotlin
data class GeneratedMember(
    val artifact: String,
    val name: String,
    val kind: GeneratedMemberKind,
    val source: String,
)

GeneratedMember(
    artifact = "Post.Companion",
    name = "fromRow",
    kind = GeneratedMemberKind.FUNCTION,
    source = "fixed entity companion decoder",
)
```

Validation groups by `(artifact, name)` and rejects any group with more than one
source unless the generator explicitly marks the duplicate as intentional. V1
should avoid intentional duplicate exceptions. It should reject by generated
source name alone: a property and a function with the same generated name in the
same artifact are a collision, even if Kotlin might distinguish some callable
forms by signature.

## Artifact Namespaces

Collisions are scoped per generated artifact. A field property can appear on
both the entity class and a mutation interface when both artifacts define that
name independently. The error is only when two generated members land in the
same artifact namespace.

### V1 artifact coverage

The first manifest implementation covers exactly the artifacts needed to
unblock the
[Field-Backed FK Declaration Names](06-field-backed-fk-declaration-names.md)
RFC plus the user-facing surfaces where collisions are most likely:

- entity data class
- entity companion object
- base mutation interface
- create builder
- update builder
- create mutation view
- update mutation view

### Phase 2 (deferred)

These artifacts are listed in the RFC's eventual coverage goal but are
**out of V1 scope**. They are added incrementally as concrete collision
scenarios warrant their own diagnostics:

- entity `Edges` class — edge-accessor namespace, distinct from the entity's
  scalar / FK namespace. The V1 manifest treats edge accessors as living on a
  different artifact than entity scalars / FKs, so a `val name = string(...)`
  + `val name = hasMany<Tag>(...)` declaration is not flagged by the manifest;
  V2 may either add coverage or rely on schema-level edge-name uniqueness
  (already enforced).
- create patch class, update patch class — patch types passed into privacy /
  validation contexts.
- create candidate class, update candidate class — `WriteCandidate` surfaces.
- create / update / load / delete privacy context classes.
- create / update / delete validation context classes.
- create / update hook context classes.
- pending-edge-ops aggregate class, per-edge pending-edge-ops view classes.

Adding each Phase 2 artifact is mechanical (mirror an existing add\*Members
function in `BuildMemberManifest.kt`); the V1 cut stops at the surface RFC 06
needs so the manifest doesn't grow ahead of validated test coverage.

## Required Coverage

V1 covers the following member kinds across the artifacts listed in
§"V1 artifact coverage":

- scalar field properties
- implicit FK properties
- field-backed FK properties
- `unset{Property}()` methods for mutable update fields and FKs (on
  `${name}UpdateMutationView` only — the public update builder doesn't
  expose them)
- `pendingEdges` aggregator on `${name}UpdateMutationView` (unconditionally
  emitted, independent of whether the schema has any helper-eligible M2M edge)
- helper-eligible M2M update mutator properties (on the public
  `${name}Update` builder)
- fixed builder members such as `save`, `saveOrError`, `saveOrThrow`, `client`,
  `driver`, hook lists, `entity`, and `dirtyFields`
- fixed entity instance members `id` and `edges`
- fixed entity companion members `fromRow`, `TABLE`, `SCHEMA`, plus per-field
  column refs, per-FK column refs, and per-edge edge refs (the edge refs are
  the V1 surface for cross-checking edge names against companion members; the
  `${Entity}Edges` *class* itself is deferred per §"Phase 2 (deferred)")
- data-class synthesized instance members `copy`, concrete component functions
  (`component1`, `component2`, etc., based on entity constructor order
  including the trailing `edges` slot when the schema has any edge),
  `toString`, `equals`, and `hashCode`

V1 should be sufficient for the field-backed FK declaration-name RFC:

- field-backed FK property name vs. scalar field, edge, implicit FK, and other
  field-backed FK names
- generated `unset{FieldBackedFkName}()` vs. scalar field properties, FK
  properties, and other generated unset methods on the update mutation view

## Diagnostics

Errors should name both sides of the collision and the generated artifact:

```text
Schema 'Post': field 'unset_author_id' generates member 'unsetAuthorId' on
PostUpdateMutationView, which collides with generated unset method for
synthesized FK for edge 'author'
```

Another expected shape:

```text
Schema 'Article': ArticleUpdateMutationView member 'unsetWriter' is generated
by field 'unset_writer' and by unset method for field-backed FK edge 'author'
```

The diagnostic should include:

- schema name
- generated artifact name
- generated member name
- generated member kind when useful
- both source declarations
- a short hint when one source is a fixed framework member

## Non-Goals

- Do not rename generated members automatically.
- Do not backtick-escape conflicting names in generated Kotlin.
- Do not introduce per-artifact override annotations in this RFC.
- Do not validate private generated implementation details unless a
  user-controlled schema name can generate the same Kotlin source name.
  Schema names currently reject leading underscores, so most private helper
  names are unreachable by user declarations.
- Do not solve every JVM signature collision in V1; this RFC focuses on
  generated Kotlin source member names. JVM signature checks can be added later
  if they become a real source of failures.

## Acceptance Criteria

- Collisions involving generated `unset{Property}()` methods are rejected.
- Collisions involving fixed hook-facing view members are rejected.
- Collisions involving field-backed FK declaration-derived names are rejected.
- Collisions involving helper-eligible M2M mutator properties and
  `pendingEdges` are rejected.
- Diagnostics identify the schema, generated artifact, generated name, and both
  source declarations.
- Existing valid schemas continue to pass without renaming.

## Test Requirements

### V1-covered cases (in `MemberCollisionValidationTest`)

- field vs. generated `unset{Property}()` collision (e.g. `unset_name` field +
  `name` field → both produce `unsetName` on `${name}UpdateMutationView`)
- companion edge ref vs. companion FK column ref (e.g. `belongsTo("author")`
  produces `authorId` column ref on companion; `hasMany("author_id")` produces
  `authorId` edge ref on the same companion → collision)
- field named `pending_edges` colliding with the unconditional
  `pendingEdges` member on `${name}UpdateMutationView` — note this fires
  regardless of whether the schema has any helper-eligible M2M edge, since
  MutationGenerator emits `pendingEdges` unconditionally
- field named `save_or_error` colliding with `saveOrError` on `${name}Create`
  and `${name}Update`
- field named `edges` colliding with entity `edges`
- field named `copy` colliding with data-class `copy` (framework-member hint
  appears in the diagnostic)
- field name `component1` colliding with the synthesized data-class
  `component1` function
- diagnostics include the schema name, generated artifact, generated member
  name, and both sources

### Now-reachable follow-up cases (after RFC 06 V1)

[RFC 06](06-field-backed-fk-declaration-names.md) V1 has shipped, which
unblocks two of the three test bullets that were previously
structurally unreachable. The manifest already handles them; the
remaining work is adding the test schemas:

- **scalar field vs. field-backed FK property collision** — now
  triggerable: scalar `val anything = string("foo_col")` produces
  entity property `fooCol`; FK with backing `val fooCol = long("backing")`
  also produces entity property `fooCol`. Different storage columns
  (`foo_col` vs `backing`), different Kotlin val names (`anything`
  vs `fooCol`), but the same generated entity-class property → the
  manifest detects the duplicate.
- **two generated `unset{Property}()` methods colliding** — falls
  out of the same construction: both the scalar and the renamed FK
  produce `unsetFooCol()` on `${name}UpdateMutationView`.

Still structurally unreachable:

- **scalar field vs. implicit FK property collision** — an implicit
  FK derives its property from `${edgeName}Id` and synthesizes
  column `${edge.name}_id`. For a scalar to share that property
  name, the scalar's `toCamelCase(column)` has to equal
  `${edgeName}Id`, which means the scalar's column has to be
  `${edge.name}_id` too — and that trips the storage-column
  collision check before the manifest sees it. No RFC unblock
  available without changing the implicit-FK column-naming
  convention itself.
