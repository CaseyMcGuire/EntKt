# RFC: Field-Level Read Privacy

## Status

Possible future feature. This is not implemented.

## Summary

Policies may attach LOAD rules to individual fields, declared in the existing
`privacy { }` block and keyed by generated column handles. Entity LOAD rules
keep running on every read, as today; field rules are evaluated for the fields
a read returns.

Field rules get their surface from projections. A projection takes an
app-owned data structure (DTO). The structure both determines which fields are
pulled and asserts their viewability — the named structure is the
authorization claim, checked strictly. A denied field fails the row's entry in
the `ReadCollectionResult`; there is no partial masking.

This RFC defines the privacy model that the
[Projection / Select API](../query/projection-select-api.md) RFC defers to.

## Principles

1. **Data is modeled for query patterns; audiences are policy.** Field privacy
   exists so that visibility distinctions never force table splits or
   migrations. Reclassifying a field's audience is a policy edit, full stop.
2. **Authorization lives in policies, not schema.** Field rules register in
   the client's `EntityPolicy`, like entity rules and validation. The schema
   gains nothing; `.sensitive()` remains a display contract only.
3. **A read asserts exactly what it returns.** Every terminal's authorization
   claim is: entity rules passed for every returned row, and field rules
   passed for every returned (row, field) pair. Callers are expected to query
   what the viewer may see; the framework verifies the claim rather than
   trimming results to fit.

## Policy API

Field rules live in the existing `privacy { }` block, keyed by the same
column handles `select()` takes — a column from another entity won't compile.
They reuse the LOAD rule types; there are no new rule interfaces.

```kotlin
privacy {
    load(allowAll)                       // entity rules, unchanged
    create(allowAll)
    update(AllowSelfUpdate())
    delete(AllowSelfDelete())

    field(User.email, User.age) {        // one block per audience cluster
        load(
            EmailNeedsAccount,           // ContextPrivacyRule — viewer-only
            AllowSelf,                   // ordinary UserLoadPrivacyRule
            AllowAdmin,
        )
    }
    // name, active: no field rules → inherit the entity decision
}
```

Same decision algebra as everything else: first non-`Continue` wins,
fallthrough denies — but the denial is scoped to the field, not the entity.

### Declaration semantics

- A field with **no** field rules inherits the entity decision. It was never
  unguarded — the entity `load(...)` rules covered its contents.
- A field **with** rules is its own allow-list: it needs an explicit `Allow`
  from its own rules, and fallthrough denies the *field*. Declaring rules is
  the author opting that field out of inheritance. (Inheriting again on
  fallthrough would silently expose precisely the fields someone tried to
  restrict.)
- A policy may opt into the strict posture per entity — `fieldsFailClosed()`
  plus a one-line `unrestricted(User.id, User.name, User.active)` naming the
  fields that ride on the entity decision. For a locked-down entity the public
  fields are usually the short list, and the declaration doubles as
  documentation of what the entity exposes.

### Context-only rules are the front door

Most real field privacy is "this class of viewer can see this field" — the
distinction between context-only and per-item rules must be preserved at
registration, because it is unrecoverable afterward:

- Context-only field rules (`ContextPrivacyRule`) are the primary path:
  evaluated once per operation, able to reject a bad select list before any
  I/O, and the only path that can ever support column pruning later.
- Per-item rules, including batch rules, are the escape hatch: full
  `PrivacyRule` power, post-fetch, no pruning ever.

Field rules should carry names — a field×viewer authorization matrix is much
harder to audit than entity rules, and explain tooling will need them.

## Evaluation Semantics

1. Entity LOAD rules run on every read, for every materialized row, as today.
2. Field rules run only for fields the terminal returns, and only for rows
   that survived entity LOAD. A denied row's field rules never evaluate —
   field rules can read through `context.client`, and they must not fire for
   rows the viewer isn't allowed to know exist.
3. **Fetched ≠ requested.** Internal hydration (rule inputs, cursor and
   ordering columns) triggers no field rules. Field rules govern disclosure
   only.
4. Context-only field rules evaluate once per operation. If one denies a field
   the terminal returns, the read fails **before any I/O** — no rows touched.
5. `ViewerContext.privacyBypass_DANGEROUS` bypasses field rules along with
   entity rules. Hooks, validation, and interceptors are unaffected, and
   trusted rule/hook code continues to see full rows.

## Read Paths

**Entity terminals** (`findById`, `all`, `firstOrNull`, eager loads,
`saveAndLoad`) return the whole entity, so their requested set is all fields:
every field rule must pass, or the read denies as it does today for entity
denial. A full read is an all-fields projection; there is no special case.

This is the escalation invariant, stated once so it is not a surprise: the day
a policy author adds one field rule to a widely-read entity, every full read
by a viewer who fails that rule starts denying. Nothing changes for a policy
until it declares field rules — but the breaking-changes log should carry this
when it ships, because someone *will* be surprised.

**Projection terminal** takes an app-owned data structure:

```kotlin
// app-owned, lives wherever the screen or endpoint lives
data class UserCard(
    val id: UUID,
    val name: String,
    val email: String,
)

val cards = client.users.query {
    where(User.active eq true)
}.select(viewerContext, UserCard::class)
// ReadCollectionResult<UserCard> — plain values, plain types.
```

- Each DTO property binds by name and type to a field of the root entity,
  validated against generated metadata on first use per (entity, DTO) pair
  and cached. A property that binds to no field, or binds with the wrong
  type, is an *operational* failure — a bug in the caller's structure — never
  a privacy denial. Rename a schema field and the DTO still compiles; it
  fails loudly at first use.
- Binding succeeds → hydrate, entity LOAD, field rules for exactly the
  structure's fields, map. A field denial makes that row's entry a
  `Failed(EntPrivacyDeniedException)`, handled by the existing result algebra
  (`getOrThrow()`, `deniedAsNull()`, per-entry inspection) with no new result
  types.
- DTO property types are the plain field types. Denial fails rather than
  nulls, so required fields stay required — the type system never lies about
  what a successful result contains.
- V1 hydrates full rows. Column pruning is a later optimization, available
  only for fields whose rules are all context-only and whose policy declares
  its rule dependencies.

The column-handle `select(viewerContext, Post.id, Post.title)` form from the
projection RFC remains valid for callers who prefer inline selection; it
obeys the same field-rule semantics. The DTO form is the preferred one across
application boundaries.

## Edges

Projections do not traverse edges. **Every property in a projection structure
must bind to a field of the root entity — and the FK column a `belongsTo`
edge creates is a field,** selectable and field-ruled like any other.

Graph-shaped reads have two sanctioned shapes: project each side and stitch
on the FK (one batched query per side, each fully checked), or use eager
entity reads under their existing contract — accepting that a full-entity
read asserts all fields of the target.

Why hold the line for v1:

- To-many edges need parameters a DTO doesn't have (which rows, what order,
  how many per parent). Encoding them in the structure costs the "just a
  data structure" simplicity this design is built on.
- To-one edges need a denial representation this design eliminated. A nested
  structure that's absent could mean "no target," "target exists but its
  entity LOAD denied," or "a field inside it was denied" — three facts the
  framework insists on keeping distinct elsewhere. Letting one screen's read
  fail because a field on a *related* entity is restricted deserves its own
  deliberate design.
- Graph reads already have an owner: eager loading's privacy contract
  (per-step batched evaluation, `filterVisible()`, `SelectedEdgePath`
  denials). Two graph-read semantics would drift.

Honest cost, written down rather than discovered: under the escalation
invariant, an eager read of an author asserts *all* of the author's fields,
including ones the screen never shows — so if `User.email` is restricted,
the eager path fails for a screen that only wanted the name. That strictness
tax is the real price of the v1 line, and the exact pressure that would
justify a nested-projections RFC later, built on the eager loader's
denial-path machinery. Nothing here forecloses it.

## Denials And Diagnostics

`PrivacyDenial` gains the field name alongside entity type and key; reasons
come from the denying rule. A denial payload never contains the field's
value. As at entity level, these payloads are trusted diagnostics —
application boundaries must not forward the absence-versus-denial
distinction, keys, fields, or reasons to untrusted clients without an
explicit mapping.

## Limitations

- **Predicate oracle.** `where`/`orderBy` over a field the viewer cannot
  read can leak its values — the field-level form of the predicate-inference
  limitation already documented for entities. One tier is closable:
  terminals reject predicates and orderings referencing *context-only-denied*
  fields, since that set is known before execution. Per-item-denied fields
  remain a documented limitation, as today.
- **No discovery.** There is no "return whatever is viewable" read. Callers
  name a structure. A visible-fields or explain API is a separate, smaller
  feature if demand appears.
- **Write side is out of scope.** This design governs read disclosure.
  Field-level *write* restrictions ("only admins may set `role`") are a
  separate, smaller feature that builds on the `FieldPatch` inputs UPDATE
  rules already receive.

## Implementation Surface

Deliberately thin, per the codegen philosophy. Codegen adds:

- the `field(...)` registration method (plus the strict-posture declarations)
  on the generated `{Entity}PrivacyScope`
- the `select(viewerContext, KClass)` terminal on generated query builders

Everything else is hand-written runtime: per-field rule lists in the privacy
config, a field phase in the LOAD evaluator parameterized by the terminal's
requested field set, DTO binding/validation/caching, and the `field` slot on
`PrivacyDenial`. No new rule interfaces, no new result types, no schema
changes.

## Considered And Set Aside

- **Split Ents as the mechanism** — Meta's precedent, and still right when
  the *data* differs (lifecycle, retention, credential-grade handling).
  Rejected as the general answer: it prices every audience change as a
  migration.
- **A generated per-field state wrapper** (Loaded/Denied/NotRequested) — the
  most expressive option, and the only one offering partial success within a
  row. Set aside because the caller's own DTO plus strict assertion covers
  the mainstream cases with no new public types; the wrapper's machinery can
  still be added later if mixed-audience single reads prove to be real demand
  rather than hypothetical.
- **Masking by default** (ZenStack/GraphQL-style field dropping) — wrong
  default here, because EntKt's callers are the app's own server code writing
  per-screen queries, not remote clients whose query documents the server
  doesn't control. Callers are expected to query data they have permission
  for.
