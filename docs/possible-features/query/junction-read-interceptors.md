# RFC: Junction Read Interceptors

## Status

Accepted direction; delivered in phases.

**Phase 1 implemented 2026-08-20**: the eager many-to-many junction
discovery read runs the junction entity's read interceptors with
`ReadOperation.EAGER_JUNCTION` before its driver read, for
`throughEntity` and `throughLink` junctions alike, with explain
mirroring (post-interceptor junction predicates and separately
attributed `QueryPlan.junctionAnnotations`).

**Phases 2+ are open**: the M2M query-chain traversal (`queryX()`) and
edge-predicate (`has {}` / `exists()`) lowerings still walk the
junction without junction-entity interceptors — see Open Phases.

## Motivation

Every junction is a generated, queryable entity — `throughEntity`
junctions by design (Membership carries payload, hooks, and privacy),
and `throughLink` junctions structurally (PostTag has a client and an
interceptor registration slot). An application that registers a read
interceptor on a junction — `ExcludeDeleted`, tenant scoping — gets it
applied to direct junction reads but, before phase 1, to no
relationship read at all: the framework treated every junction read as
internal storage and queried it with only the structural source-ID
predicate.

The consequence was a silent policy bypass: `group.loadUsers()` could
expose a relationship contributed by a soft-deleted or cross-tenant
membership row that `client.memberships.query()` would never return.
The read-path interceptor RFC's own contract — "no generated read
bypasses interceptors" — points the other way, and the soft-delete RFC
explicitly deferred the M2M gaps to the read-path implementation.

## Model (Phase 1: Eager Discovery)

An eager M2M step now begins with a **junction discovery pass**:

1. Construct the junction entity's query and seed it with the eager
   step's traversal context.
2. Run the junction entity's read interceptors with
   `ReadOperation.EAGER_JUNCTION` and the structural
   `junctionSourceColumn IN (source IDs)` predicate.
3. Execute the junction driver read with the post-interceptor
   predicates and ordering (still gated on a non-empty parent set).
4. Continue with the existing step: discovered target values feed the
   target entity's `EAGER_LOAD` interceptor pass, the target read,
   windows, privacy, and the set-based nested pass.

Contract:

- **Context convention.** `currentEntity` is the junction entity;
  `sourceEntity`, `edgeName`, and `path` describe the eager M2M step
  being discovered (`path.last()` names the edge's declared source and
  target — no schema edge names the junction). `isEagerSubquery` is
  true.
- **Limit ops are silent no-ops.** Discovery must see every
  membership; a limit would silently drop associations. `addPredicate`,
  `addAnnotation`, and `reject` apply normally.
- **One pass per logical step, data-independent.** The pass runs
  exactly once per configured M2M step, even when the parent set is
  empty (empty structural `IN`); only the driver read is data-gated.
  Future physical chunking stays below this boundary.
- **Precedence.** Junction interceptor rejection stops the step before
  junction I/O and before the target interceptor pass. A junction
  driver failure still precedes the target interceptor pass.
- **Junction LOAD privacy deliberately does not run.** Discovery never
  materializes junction rows to the caller, and the privacy model is
  fail-closed — a junction privacy pass would fail every M2M read for
  schemas without junction Allow rules. Interceptors ≠ privacy; a
  junction-privacy design would be its own RFC.
- **Discovery stays unordered by default.** The junction read carries
  whatever ordering interceptors contribute (normally none); the
  framework adds no primary-key term here — target-side effective
  ordering is what drives result determinism.
- **Null-FK junction rows keep their contract.** Null source FKs never
  match the structural `IN`; null target values still flow into the
  target pass's structural predicate and never become associations.
  Interceptor predicates may incidentally narrow them (narrowing is
  always legal).
- **Explain mirrors the pass.** The junction explain uses the
  post-interceptor junction predicates; junction annotations surface
  on the typed `QueryPlan.junctionAnnotations` field (never merged
  into the target spec's caller-controlled map); a junction rejection
  renders as a rejected edge entry.

The target interceptor's structural target-value predicate now
contains only values discovered through the post-interceptor junction
read — junction interceptors narrow what target interceptors ever see.

## Open Phases

- **Query-chain traversal (`queryX()`).** `HasM2MEdgeFromShape` lowers
  to a junction subquery inside the driver with join columns only. The
  predicate shape has no slot for junction-side predicates, so this
  phase needs an AST extension plus driver-lowering changes.
- **Edge predicates (`has {}` / `exists()`).** Same shape problem:
  `HasEdge`/`HasEdgeWith` lower to an EXISTS over the junction with no
  junction predicate slot.
- **Edge-mutation diff read.** `tags.add/remove` reads current
  junction rows without interceptors. Link junctions are payload-free
  by validation, so no soft-delete column can exist there; whether
  tenant-style junction interceptors should scope the diff read is an
  open write-path question.

Until those phases land, a junction interceptor narrows direct reads
and eager loading but NOT `queryX()` traversal or `has {}` predicates —
an inconsistency this RFC exists to remove. `ExcludeDeleted`'s KDoc
states the exact coverage.

## Test Requirements (Phase 1)

- A junction interceptor predicate narrows eager discovery to the same
  rows a direct junction read returns, for `throughEntity` and
  `throughLink` junctions.
- The pass exposes `EAGER_JUNCTION`, the junction as `currentEntity`,
  the eager step's source/edge/path, and the structural source-ID `IN`.
- A junction rejection fails the read before junction I/O and before
  any target interceptor callback.
- The pass runs exactly once with an empty structural `IN` when no
  parents exist, with no junction driver read.
- Explain shows post-interceptor junction predicates, separately
  attributed junction annotations, and junction rejections as rejected
  edge entries.

## Resolved Decisions

- A dedicated `ReadOperation.EAGER_JUNCTION` rather than reusing
  `EAGER_LOAD`: the junction is `currentEntity` while the path names
  the edge's target, and interceptors must be able to tell a discovery
  read from the target read.
- Both junction kinds run the pass — every junction is an entity with
  a registration surface.
- No junction LOAD privacy on discovery (fail-closed would break every
  M2M read); revisit only via its own RFC.
- Phase 1 before the traversal/predicate phases: eager discovery needs
  no AST or driver changes.

## Related Features

- [Set-Based Eager Graph Loader](set-based-eager-graph-loader.md)
- `docs/implemented-features/query/read-path-interceptors.md`
- `docs/implemented-features/schema/soft-delete.md`
