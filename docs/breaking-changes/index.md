# Breaking Changes

entkt is pre-1.0 and not yet used in production, so breaking changes are
expected and intentional (see the [project principles](../../AGENTS.MD)).
This file is the single running log of every breaking change to the public
surface — the schema DSL, the generated API (entities, repos, query
builders, `indexes` helpers), the runtime, the drivers, and the Gradle
plugin. **Newest first.**

This is the source of truth for "what do I have to change when I bump
entkt." The numbered user guides describe the *current* API; this log
describes how it *changed*.

## Adding an entry

Add a bullet under `## Unreleased` (create that section if it's missing),
newest at the top, using this shape:

```text
- **<short imperative summary>** (`affected-module`)
  <what changed and why, in a sentence or two>
  _Migration:_ <the concrete change a caller must make>
```

Keep it caller-focused: what breaks and what to do about it, not the
internal rationale (link an [implemented-features](../implemented-features/index.md)
note for the full design). When cutting a release, rename `## Unreleased`
to the version (e.g. `## 0.2.0`) and start a fresh empty `## Unreleased`
above it.

## Unreleased

- **Allow explicit raw storage reads in privacy rules** (`codegen`)
  `rawCount()`, `rawExists()`, and raw aggregate terminals no longer fail on
  `EntPrivacyReadClient`. Their `raw` prefix now has one consistent meaning in
  every read posture: they run read interceptors but do not materialize
  entities or evaluate LOAD privacy. Materializing reads through privacy-rule
  clients remain viewer-scoped and LOAD-checked.
  _Migration:_ audit privacy rules that call raw terminals. Keep them when a
  storage-level fact is intentional; use `findById`, `firstOrNull`, or `all`
  when the referenced entity's visibility must participate in authorization.

- **Reject captured root-client work inside its transaction** (`codegen`, `runtime`, `postgres`)
  Reads, mutations, and another `withTransaction` call through the same root
  client no longer execute on an independent connection from inside that
  root's transaction block. Reads and mutations fail before callbacks or I/O;
  nested root transaction entry throws `NestedTransactionUnsupportedException`.
  PostgreSQL also rejects direct root-driver I/O in the same synchronous
  execution.
  _Migration:_ use the `EntTransactionClient` supplied to the block for every
  operation that runs inside it. Pass `EntClientScope` or the required
  repositories into helpers instead of capturing the root client.

- **Share repository helpers safely across root, transaction, and hook clients** (`codegen`)
  Generated `EntClient` and `EntTransactionClient` now implement
  `EntClientScope`, which exposes repositories and `currentPrivacyContext()`
  but omits transaction entry, privacy re-scoping and bypass, and client
  configuration. `beforeCreate` and `beforeUpdate` hook contexts now type
  `ctx.client` as this interface and receive a narrow facade, so
  `ctx.client.withTransaction { ... }` no longer compiles.
  _Migration:_ type helpers that need repositories from either client as
  `EntClientScope`. Hook code using repository access is unchanged; move any
  transaction, privacy re-scoping, bypass, or configuration call out of the
  hook context and perform it explicitly at the application boundary.

- **Make `getOrThrow()` a member of every operation result** (`runtime`)
  `ReadResult`, `MutationResult`, and `TransactionResult` now declare
  `getOrThrow()` directly instead of relying on overloaded top-level
  extensions. Its behavior is unchanged; `visibleOrNull()` and
  transaction-scoped `orRollback()` remain extensions because their receiver
  constraints are part of their contracts.
  _Migration:_ remove `import entkt.runtime.result.getOrThrow`; ordinary
  receiver calls such as `result.getOrThrow()` are otherwise unchanged.

- **Rethrow confirmed transaction failures directly; reserve a dedicated exception for uncertain outcomes** (`runtime`)
  `TransactionResult.getOrThrow()` no longer wraps every failure in
  `EntTransactionFailedException`. A `NotCommitted` result now rethrows
  the exact stored exception, restoring ordinary typed catches for
  application, validation, and privacy failures. An `OutcomeUnknown`
  result throws `EntTransactionOutcomeUnknownException`, which exposes
  the exact stored exception through `exception` and `cause`. Read and
  mutation privacy-denial exceptions now share the sealed
  `EntPrivacyFailure` classification marker.
  _Migration:_ replace `catch (EntTransactionFailedException)` with
  ordinary typed catches plus a dedicated
  `catch (EntTransactionOutcomeUnknownException)` branch. Inspect the
  raw `TransactionResult.Failed` when both the exception and explicit
  `transactionState` are needed without throwing.

- **Canonical operation-result algebra: every generated data operation returns an exhaustive result** (`runtime`, `codegen`, `postgres`)
  The result-variants API (`*OrThrow` / `*OrNull` / `*OrError` / `visible*`
  generated terminal families, `EntResult`, the universal `EntError`
  hierarchy, and `EntException.error`) is replaced by one canonical
  result-bearing terminal per operation family plus runtime projections.
  See the [operation-result-algebra design](../implemented-features/api/operation-result-algebra.md)
  for the full contract. In caller terms:
  - *Reads.* `byIdOrNull` / `byIdOrThrow` / `byIdOrError` /
    `visibleByIdOrNull` collapse to `findById(id): ReadResult<Entity?>`;
    `allOrThrow` / `allOrError` to `all(): ReadResult<List<Entity>>`;
    `firstOrNull` / `firstOrThrow` / `firstOrError` to
    `firstOrNull(): ReadResult<Entity?>`; `rawCount` / `rawExists` and the
    raw aggregates return `ReadResult<...>` and lose their `*OrError`
    twins. `Success(null)` is authoritative absence; LOAD denial is
    `Failed(EntPrivacyDeniedException(origin, denials))` with keyed,
    ordered denials (strict `all()` reports every denied root row in the
    selected window). Project with `.getOrThrow()` for throwing behavior
    and `.visibleOrNull()` for privacy-as-absence
    (`visibleByIdOrNull(id)` → `findById(id).visibleOrNull().getOrThrow()`).
  - *Privacy-scanning terminals removed.* `visibleAll`,
    `visibleAllOrError`, `firstVisibleOrNull`, `visibleCount`,
    `visibleExists`, the index-helper `visibleOrNull`, the
    `visibleOverfetchLimit` config knob, `EntError.OverfetchCapExceeded`,
    and `ReadOperation.VISIBLE_COUNT` / `VISIBLE_EXISTS` are gone with no
    canonical equivalent (privacy-skipping scans are an explicit
    non-goal). Interceptors branching on those enum entries must drop the
    branches. The debug viewer's list endpoint now reports an explicitly
    privacy-filtered empty page when any row in the window is denied; run
    it with a bypass-scoped client for full listings.
  - *Unique-index helpers.* The four stage terminals (`orNull` /
    `visibleOrNull` / `orError` / `orThrow`) collapse to
    `find(): ReadResult<Entity?>`; `query()` remains.
  - *Mutations.* Builder saves become `save(): MutationResult<Unit>` and
    `saveAndLoad(): MutationResult<Entity>` (replacing `save` /
    `saveOrNull` / `saveOrThrow` / `saveOrError`); deletes become
    `delete(entity): MutationResult<Unit>` (idempotent; success means the
    row is absent afterward) and `deleteById(id): MutationResult<Boolean>`
    (`Success(true)` only when this call deleted the row);
    `deleteMany(...): MutationResult<Int>` and
    `createMany(...): MutationResult<List<Entity>>` are atomic — EntKt
    owns one transaction when the caller does not, and `createMany` no
    longer requires a caller transaction. Every mutation failure is a
    typed `EntMutationException` carrying `MutationWriteState`
    (`NotPersisted` / `TransactionPending` / `Committed` /
    `PersistenceUnknown`); `.getOrThrow()` throws that stored exception
    directly — `getOrThrow()` is the sole throwing projection on all
    three result types, with no `orThrow()` alias. Pre-write failures (target absent, validation, mutation
    privacy, recognized constraints, conflicts) hardcode `NotPersisted`;
    a returned-entity disclosure denial from `saveAndLoad()` uses
    `EntMutationPrivacyDeniedException` with `operation = LOAD` and the
    real write state (possibly `Committed`).
  - *Assignment-free updates succeed.* `update(id) {}` no longer throws
    `NoChanges`: it verifies the target exists (absent →
    `Failed(EntTargetAbsentException)` — note this now discloses
    existence where the old order did not), runs pre-write phases, skips
    persist and post-persist callbacks, and returns `Success`.
  - *Exception capture boundary.* Ordinary exceptions raised inside a
    result-bearing terminal — including hook/rule bugs,
    `TransactionRequiredException`, and
    `UnsupportedDriverCapabilityException`, which previously propagated
    by contract — are captured as `Failed(EntUnexpectedMutationException)`
    (or stored directly for reads). `CancellationException` and JVM
    `Error`s still propagate. Builder/DSL argument validation still
    throws.
  - *Transactions.* `withTransaction` becomes
    `fun <T> withTransaction(block: TransactionScope.(EntTransactionClient) -> T): TransactionResult<T>`;
    `withTransactionOrError` and `bind()` are removed — use
    `orRollback()` on read/mutation results inside the block, and
    `.getOrThrow()` on the returned `TransactionResult`
    (confirmed rollback rethrows the stored exception directly;
    `OutcomeUnknown` throws `EntTransactionOutcomeUnknownException`). A mutation failure produced
    through the transaction client marks the scope rollback-only even if
    its result is ignored — a normally returning block then rolls back
    and reports the first recorded failure with later ones suppressed.
    The generated `EntTransactionClient` exposes repositories and
    privacy re-scoping but no `withTransaction` member, so nested client
    transactions no longer compile. Helpers that need the shared repository
    surface should accept `EntClientScope`; narrower helpers may accept the
    repositories they actually use. Nested driver transactions remain
    unsupported and throw `NestedTransactionUnsupportedException`
    before the nested block runs.
  - *Driver SPI.* `Driver.withTransaction` returns
    `DriverTransactionResult<T>` with `TransactionFailureState`
    (`Success` only after confirmed commit; commit failure is
    `OutcomeUnknown` even if a later rollback appears to succeed), and
    `Driver.classifyException` is replaced by the mutation-only
    `Driver.classifyMutationException(exception, entity, operation):
    EntMutationException?` — the returned exception's own `writeState`
    is the classification, with no parallel state field. Postgres maps
    SQLSTATE 23xxx to `EntConstraintViolationException` and
    40001/40P01 to `EntConflictException`. The top-level
    `classifyDriverError` helper is removed.
  - *Interceptor rejection payload.* `EntQueryRejectedException` exposes
    direct properties (`entityType`, `reason`, `code`, `interceptor` — no
    `operation` field, no `EntError` payload); `QueryPlan.rejection`
    stores that exception, and `requireNotRejected()` throws it.
    `explain*()` terminals keep their `QueryPlan` contract but are
    renamed to track the canonical terminals (`explainAll`,
    `explainFirstOrNull`, `explainFindById`, `explainRawCount`,
    `explainRawExists`).
  Generated output now uses Kotlin 2.3 expression-body syntax
  (`= try { ... return ... }`), so projects compiling generated code
  need a correspondingly recent Kotlin compiler.
  _Migration:_ replace each removed terminal with its canonical
  replacement plus a projection: `byIdOrThrow(id)` →
  `findById(id).getOrThrow()!!` (or handle `Success(null)` explicitly);
  `allOrThrow()` → `query { ... }.all().getOrThrow()`;
  `create { }.saveOrThrow()` → `create { }.saveAndLoad().getOrThrow()`;
  `deleteByIdOrError(id).getOrThrow()` → `deleteById(id).getOrThrow()`;
  `withTransactionOrError { bind() }` → `withTransaction { orRollback() }.getOrThrow()`.
  Structured handling moves from `EntError` pattern-matching to exhaustive
  `when` over `ReadResult` / `MutationResult` variants and typed-exception
  properties. Catch sites of `TransactionRequiredException` /
  `UnsupportedDriverCapabilityException` around saves must inspect the
  returned `Failed(EntUnexpectedMutationException)` instead.

- **Remove the M2M link-edge `.readOnly()` modifier** (`schema`, `codegen`)
  Every declared `manyToMany(...).throughLink(...)` edge now exposes the
  same read and `add` / `remove` / `set` surface. There is no
  traversal-only link-edge variant. The public
  `ManyToManyThrough.LinkTable` metadata also no longer has a `readOnly`
  property, constructor / `copy` parameter, or fourth destructuring
  component.
  _Migration:_ remove `.readOnly()`. Keep the declaration if that endpoint
  should remain traversable and accept its write helpers; otherwise omit the
  declaration and query the junction explicitly when reverse traversal is
  needed. Use `throughEntity(...)` when junction writes must go through the
  junction repository. Code that inspects `ManyToManyThrough.LinkTable`
  should remove `readOnly` branches and treat every instance as writable.

- **Nested transaction blocks are savepoint-scoped; an aborted transaction can no longer "commit"** (`postgres`)
  A nested `withTransaction` / `withTransactionOrError` previously reused
  the outer transaction with no rollback of its own: a nested block that
  failed after some SQL returned `Err` while its partial writes silently
  committed with the outer transaction, and a failed nested statement
  left the whole transaction in PostgreSQL's aborted state — where the
  JDBC driver silently turns `COMMIT` into `ROLLBACK` while reporting
  success. Nested blocks now run under a savepoint (a failed block rolls
  back exactly its own writes and the outer transaction stays usable),
  and both commit paths fail loudly with an explanatory
  `IllegalStateException` if the block swallowed a failed statement's
  error and continued.
  _Migration:_ none for code that stops at the first failure. Code that
  intentionally handles an `Err` from a failed SQL statement inside a
  transaction block and continues must scope that fallible step in a
  nested `withTransactionOrError` block (which isolates it under a
  savepoint) — previously it appeared to work while silently discarding
  the entire transaction.

- **Generated edge properties are `EdgeState` values instead of nullables** (`codegen`, `runtime`)
  Each property on a generated `Edges` class is now an explicit
  `entkt.runtime.query.EdgeState` defaulting to `EdgeState.Unloaded`:
  to-many and M2M edges are `EdgeState<List<Target>>`, and every
  to-one edge (`hasOne` and `belongsTo`, even over a required FK) is
  `EdgeState<Target?>`. Eager loading wraps every requested edge in
  `EdgeState.Loaded(...)`. The three states are distinct:
  `Unloaded` (the query never requested the edge), `Loaded(null)`
  (a to-one edge was requested but no target was returned), and
  `Loaded(emptyList())` (a to-many edge was requested and no rows
  matched). Previously one `null` covered both "not loaded" and, for
  to-one edges, "loaded with no row". See the
  [loaded edge state note](../implemented-features/query/loaded-edge-state.md).
  _Migration:_ replace nullable edge access with the `EdgeState`
  helpers: `user.edges.posts!!` / `user.edges.posts.orEmpty()` →
  `user.edges.posts.requireLoaded()` (throws `EdgeNotLoadedException`
  if the edge wasn't requested) when the query always requests the
  edge; `loadedOrNull()` when you must distinguish all three states;
  `valueOrNull()` only when collapsing `Unloaded` and `Loaded(null)`
  is intentional. `edges.posts == null` checks are never true now —
  the compiler only warns, so audit them by hand.

- **Auto-DDL fails on pre-existing tables whose body differs from the schema** (`postgres`)
  Under `autoDdl = true`, registration now introspects tables that
  already exist and compares columns, types, nullability, and primary
  key against the requested schema (via the migration engine's
  normalizer/differ; column `DEFAULT`s are not compared, and
  `GENERATED AS IDENTITY` ids count as `serial`/`bigserial`).
  Previously `CREATE TABLE IF NOT EXISTS` silently accepted any
  existing table and the first query or write failed — or ran under
  different constraints than declared. A mismatch now fails
  registration loudly, naming the drift, with nothing cached.
  _Migration:_ none for compatible tables (equivalent bodies register as
  before). For drifted tables, reconcile with a migration
  (docs/09-migrations.md) or drop and re-register; auto-DDL never alters
  an existing table.

- **`RelationshipLockKey` rejects unsorted FK columns** (`runtime`)
  The public data-class constructor (and `copy()`) now `require`s
  `fkColumns` in canonical sorted order instead of silently representing
  a key that would hash to a different advisory lock and lose
  cross-orientation serialization. Generated saves already pass sorted
  columns and are unaffected.
  _Migration:_ build keys via `RelationshipLockKey.canonical(...)` (which
  sorts); direct construction must pass an already-sorted list.

- **`EntReadClient` split into an interface plus posture-specific concrete clients** (`codegen`)
  Generated `EntReadClient` is now an interface, not a concrete class.
  Validation contexts expose the new concrete `EntValidationReadClient`
  (reads bypass LOAD privacy; raw terminals work) and privacy contexts
  expose the new concrete `EntPrivacyReadClient` (reads are viewer-scoped;
  raw terminals keep throwing `IllegalStateException`) — the posture that
  was previously hidden instance state is now visible in the context
  types. Both concrete types implement `EntReadClient`, and the
  `ctx.client.<repo>` call shape is unchanged. The internal
  `asReadClientForInternalUse(context)` adapter is replaced by
  `asValidationReadClientForInternalUse()` /
  `asPrivacyReadClientForInternalUse(privacy)`. See the
  [posture-specific read clients note](../implemented-features/privacy-validation/posture-specific-read-clients.md).
  _Migration:_ posture-agnostic helper parameters may remain
  `EntReadClient` (such helpers must not use raw terminals); helpers that
  rely on privacy-bypassing or raw reads should retype to
  `EntValidationReadClient`, and helpers that participate in
  authorization decisions to `EntPrivacyReadClient`; unsupported code
  constructing the old concrete `EntReadClient` or calling
  `asReadClientForInternalUse` must stop — the generated evaluators are
  the only construction path.

- **Eager-load interceptors fire on empty results** (`codegen`)
  The EAGER_LOAD interceptor pass now runs on every configured
  `with{Edge}()` subquery even when the root query matched nothing —
  including the `firstOrNull` / `firstVisibleOrNull` no-match paths and
  nested eager loads with no parent groups — matching `explain()` and the
  existing relationship-empty / `limit(0)` behavior. Driver fetches are
  still skipped when nothing could match. An interceptor that `reject()`s
  on `EAGER_LOAD` now rejects such reads: `firstOrNull` throws
  `EntQueryRejectedException` instead of returning null, `firstOrError`
  returns `Err(QueryRejected)` instead of `Err(NotFound)`, and
  `allOrThrow` on an empty root throws instead of returning `[]`.
  _Migration:_ interceptors that reject on `EAGER_LOAD` must tolerate
  empty-batch passes (or callers must expect the rejection on empty
  results); observing interceptors see one additional `EAGER_LOAD` pass
  per configured edge on empty reads.

- **`queryX()` traversal follows the source query's shape; two new sealed `Predicate` bridges** (`schema`, `codegen`, `postgres`, `runtime`)
  Edge traversal no longer drops source `orderBy` / `limit` / `offset` at the
  bridge: generated `queryX()` embeds the post-interceptor source shape in the
  new sealed `Predicate` subclasses `HasEdgeFromShape` / `HasM2MEdgeFromShape`
  (carrying `entkt.query.TraversalSourceShape`), lowered as a source-id
  subquery, and interceptor limit operations now apply to
  `ReadOperation.EDGE_TRAVERSAL` instead of silently no-oping (see the
  [shape-preserving traversal note](../implemented-features/query/edge-traversal-source-shape.md)).
  _Migration:_ traversal results can narrow — drop source bounds that the old
  lowering silently ignored if the broad row set was actually intended; add
  branches for the two new subclasses to any exhaustive `when` over
  `Predicate`; custom drivers must lower the shaped bridges (silently falling
  back to predicate-only traversal is not allowed).

- **`QueryFlag` moved from `entkt.runtime.query` to `entkt.query`** (`runtime`, `schema`)
  The enum now lives in the schema module so `TraversalSourceShape` can carry
  the source step's flags without `:schema` depending on `:runtime`.
  _Migration:_ update imports to `entkt.query.QueryFlag`.

- **Privacy-rule and validator contexts expose a read-only `EntReadClient`** (`codegen`, `runtime`)
  `ctx.client` on the generated privacy rule contexts (LOAD / CREATE /
  UPDATE / DELETE) and validator contexts is now the read-only
  `EntReadClient` instead of the full `EntClient`: writes, transactions,
  and re-scoping no longer compile from rule or validator code. The two
  surfaces deliberately differ in posture: privacy-rule readers are
  **viewer-scoped** — rule reads see what the viewer being authorized can
  see, and raw terminals (`rawCount` / `rawExists` / raw aggregates,
  including the `*OrError` variants) throw `IllegalStateException` at
  runtime — while validator readers are **privacy-bypass-scoped** —
  validation reads see all rows and raw terminals keep working. Hook
  contexts keep the full `EntClient`. See the
  [read-only privacy client note](../implemented-features/privacy-validation/read-only-privacy-client.md).
  _Migration:_ retype rule/validator helper signatures from `EntClient` to
  `EntReadClient`; in privacy rules, replace privacy-bypassing loads with
  LOAD-checked reads (`byIdOrNull` throws on a denied row) or the
  `visible*` family (a denied row collapses to null/absent); move writes
  out of rules and validators into hooks or callers.

- **`io.entkt:ent-viewer` renamed to `io.entkt:ent-viewer-core`** (`ent-viewer`)
  The viewer family now lives under one `ent-viewer/` folder as
  `ent-viewer-core` (framework-neutral) and `ent-viewer-spring` (Spring Boot
  auto-configuration that mounts an application-declared `EntViewer` bean).
  Kotlin packages are unchanged (`entkt.viewer`, `entkt.viewer.spring`).
  _Migration:_ change the dependency coordinate to
  `io.entkt:ent-viewer-core`; Spring Boot apps can add
  `io.entkt:ent-viewer-spring` and delete their hand-written mount.

- **`ColumnMetadata` gains `sensitive: Boolean = false` (positional shift)** (`runtime`)
  `.sensitive()` now flows into runtime column metadata (it powers the ent
  viewer's redaction and is a framework-wide display contract). The new
  parameter sits between `comment` and `default`, so positional construction
  past `comment` shifts; named-argument construction (the norm) is
  unaffected.
  _Migration:_ use named arguments for hand-built `ColumnMetadata`.

- **Typed JSON is mapper-pluggable: `JsonColumnMetadata` reshaped, `PostgresDriver(json)` replaced** (`runtime`, `postgres`, `codegen`)
  JSON columns flow through a driver-level `JsonColumnCodec` (kotlinx
  default; Jackson via the new `io.entkt:jackson` module, selected at
  codegen time with `entkt { jsonMapper }`). `JsonColumnMetadata(klass,
  serializer, typeName)` became `JsonColumnMetadata(klass, kType, typeName,
  mapper, kotlinxSerializer?)`, and `PostgresDriver`'s `json:
  Json` constructor parameter is now `jsonCodec: JsonColumnCodec`. See
  [Pluggable JSON Mappers](../implemented-features/schema/pluggable-json-mappers.md).
  _Migration:_ regenerate code (the `SCHEMA` literal shape changed); replace
  `PostgresDriver(ds, json = Json {...})` with
  `PostgresDriver(ds, jsonCodec = KotlinxJsonCodec(Json {...}))`; hand-built
  metadata adds `kType = typeOf<T>()` and `mapper = JsonMapperIds.KOTLINX`,
  renames `serializer` to `kotlinxSerializer`, and must now supply
  `typeName` (previously optional, now required for diagnostics).

- **`Field.jsonClass` constructor parameter replaced by `Field.jsonType: KType`** (`schema`)
  JSON fields now capture the full Kotlin type (with type arguments) so
  `json<List<Rect>>("rects")` generates a `List<Rect>` property and registers
  an element-typed serializer — a `KClass` cannot carry type arguments, which
  is why generic JSON fields previously emitted a raw `List`. `Field.jsonClass`
  remains as a derived read-only property (the raw classifier), so reads keep
  compiling; only constructing/copying `Field` with a named `jsonClass`
  argument breaks. `json(name, klass)` now rejects classes with type
  parameters (use the reified overload). `FieldBuilder.setJsonClass` is now
  `setJsonType(KType)`.
  _Migration:_ pass `jsonType = typeOf<X>()` instead of `jsonClass = X::class`
  when constructing `Field` directly; schema DSL callers (`json(...)`) need no
  change.

- **`entkt.runtime` split into concern-based subpackages** (`runtime`)
  Runtime types moved from the flat `entkt.runtime` package into
  `entkt.runtime.{driver,privacy,validation,query,mutation,result}`.
  _Migration:_ update imports — e.g. `entkt.runtime.Viewer` →
  `entkt.runtime.privacy.Viewer`, `entkt.runtime.Driver` →
  `entkt.runtime.driver.Driver`, `entkt.runtime.EntResult` →
  `entkt.runtime.result.EntResult`. Generated code already targets the new
  packages; only hand-written imports need updating. See the mapping in
  [runtime/README.md](../../runtime/README.md#package-layout).
