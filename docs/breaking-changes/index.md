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

- **Separate update drafts from executable update mutations** (`codegen`, `runtime`)
  Generated `${Entity}Update` classes are replaced by state-only
  `${Entity}UpdateDraft` values. Repository `update(id) { ... }` now returns a
  generic `UpdateMutation<Draft, Entity>` whose `configure { ... }`, `save()`,
  and `saveAndLoad()` methods own the operation. The mutation is single-use,
  matching `CreateMutation`; its first save terminal consumes it.
  _Migration:_ replace explicit `${Entity}Update` type references with
  `${Entity}UpdateDraft`, keep configuration inside `update { ... }` or
  `.configure { ... }`, and invoke `save(viewerContext)` or
  `saveAndLoad(viewerContext)` on the returned `UpdateMutation`.

- **Remove generated raw query terminals** (`codegen`)
  Generated query builders no longer expose `rawCount`, `rawExists`,
  `rawMin` / `rawMax` / `rawSum` / `rawAvg`, or their grouped `*By`
  variants. Generated reads now have one entity-materializing privacy model.
  _Migration:_ use `firstOrNull(viewerContext)` for existence and
  `all(viewerContext)` plus Kotlin collection operations for counts and
  aggregates. When the calculation intentionally includes rows hidden from
  the application viewer, pass
  `ViewerContext.privacyBypass_DANGEROUS(reason)` to that entity terminal.

- **Pass `ViewerContext` explicitly to every entity operation** (`runtime`, `codegen`, `ent-viewer`)
  Ambient `PrivacyContext` lookup and generated client-scoping APIs have been
  removed. Generated clients, transaction clients, executors, and rule-read
  clients are now long-lived and contextless; every read, create/update save,
  delete, and bulk terminal takes a context-first
  `ViewerContext`. The exact supplied instance flows through interceptors,
  privacy rules, hooks, eager loads, transactions, and returned-entity LOAD
  checks. Validation rules expose a separate privacy-bypassing
  `readViewerContext`, and Ent Viewer configuration is now `viewerContext`.
  _Migration:_ replace `PrivacyContext` with
  `entkt.runtime.privacy.ViewerContext`; remove client `privacyContext { ... }`,
  `currentPrivacyContext()`, `withPrivacyContext`, and block-scoped bypass
  calls; construct a context at the request/job boundary and pass it as the
  first argument to every executing terminal. In privacy rules and create/update
  hooks, pass `context.viewerContext` to nested operations; in validation rules,
  pass `context.readViewerContext`. Replace bypass blocks with
  `ViewerContext.privacyBypass_DANGEROUS(reason)` and pass that value directly.
  Rename Ent Viewer configuration from `privacyContext { request -> ... }` to
  `viewerContext { request -> ... }`.

- **Pass Ent Viewer entity lists directly** (`ent-viewer`, `codegen`)
  `EntViewerRegistry` has been removed, and `GeneratedEntViewerRegistry` is now
  the generated `List<EntViewerEntity<EntClient>>` itself.
  _Migration:_ pass the generated value unchanged at positional call sites; for
  custom registries, pass their `entities` list directly to `EntViewer`.

- **Use one `ReadOnlyEntClient` in all rule contexts** (`codegen`)
  `EntPrivacyReadClient`, `EntValidationReadClient`, and `EntReadClient` have
  been replaced by one stable, contextless `ReadOnlyEntClient`. The client type
  no longer implies a privacy posture; each terminal's explicit `ViewerContext`
  determines the read behavior.
  _Migration:_ change rule and helper signatures to `ReadOnlyEntClient`, pass
  `context.viewerContext` for viewer-scoped rule reads, and pass
  `context.readViewerContext` for validation bypass reads.

- **Construct generated repositories with complete client configuration** (`codegen`, `runtime`)
  Generated repository constructors are now internal and receive their client,
  hooks, privacy rules, and validation rules up front. Repositories no longer
  expose attach/apply/copy wiring methods or implement the runtime
  `CreateMutationSpec`; create execution receives a separate immutable spec.
  The public client constructor snapshots every hook, privacy, validation, and
  interceptor registry before constructing repositories, so retained DSL
  scopes cannot mutate a live client or any client derived from it.
  _Migration:_ construct repositories through `EntClient` instead of calling a
  `${Entity}Repo` constructor directly. Internal integrations that implemented
  `CreateMutationSpec` should instantiate it with the entity mapping, draft
  resolver, hook lists, and CREATE privacy and validation rule lists. Generated
  repositories now pass each draft's hook views separately; `MutationExecutor`
  owns rule evaluation, persistence, and returned-entity LOAD authorization.

- **Separate create drafts from executable create mutations** (`codegen`, `runtime`)
  Generated `${Entity}Create` builders are replaced by state-only
  `${Entity}CreateDraft` values. Repository `create { ... }` now returns a
  generic `CreateMutation<Draft, Entity>` whose `configure { ... }`, `save()`,
  and `saveAndLoad()` methods own the executable operation. Draft fields are
  nullable while the draft is incomplete, reading an unspecified field returns
  `null`, and `isSet(Entity.field)` distinguishes unspecified from explicitly
  assigned `null`. A create mutation is single-use: its first save terminal
  consumes it, and later configuration or save attempts throw
  `EntMutationAlreadyConsumedException`. `createMany` blocks configure plain
  drafts and no longer expose scalar save terminals.
  _Migration:_ replace explicit `${Entity}Create` type references with
  `${Entity}CreateDraft`; move conditional changes after `create { ... }` into
  `.configure { ... }`; guard reads that must distinguish omission with
  `isSet(Entity.field)`; and move any `save()` / `saveAndLoad()` call out of a
  `create` or `createMany` configuration block and onto the returned mutation.

- **Rename eager LOAD-denial path types around selected edges** (`runtime`)
  Related-entity LOAD denials now report `LoadDenialOrigin.SelectedEdgePath`
  containing `SelectedEdgeStep` values; the names distinguish the complete path
  from each individual edge and align with the selected-graph API.
  _Migration:_ replace `LoadDenialOrigin.EagerEdge(path)` with
  `LoadDenialOrigin.SelectedEdgePath(steps)`, replace `EagerEdgeStep` with
  `SelectedEdgeStep`, and read the path through `steps` instead of `path`.

- **Rename `Driver` to `DatabaseDriver`** (`runtime`, `postgres`)
  The public storage-driver interface is now named `DatabaseDriver`; its
  behavior and responsibilities are unchanged.
  _Migration:_ replace imports and type references to
  `entkt.runtime.driver.Driver` with `entkt.runtime.driver.DatabaseDriver`, and
  update custom driver implementations and decorators to implement the renamed
  interface.

- **Remove query-explanation APIs** (`runtime`, `codegen`, `postgres`)
  Generated queries and repositories no longer expose `explainAll`,
  `explainFirstOrNull`, `explainRawCount`, `explainRawExists`, or
  `explainFindById`. The runtime `QueryPlan` / `QueryExplanation` model and
  driver `explainQuery` / `explainCount` hooks have also been removed.
  _Migration:_ remove calls to the deleted `explain*` methods. Use ordinary
  query terminals plus application-level database observability when execution
  diagnostics are required.

- **`Driver` gains two abstract members for native direct to-many windows** (`runtime`, `postgres`)
  `directToManyWindowCapability()` and `queryDirectToMany(query)` are new
  and deliberately abstract (like `registerAll` / `requireBindCapacity`),
  so hand-written drivers and decorators no longer compile until they
  choose: report `DirectToManyWindowCapability.EMULATED` with a throwing
  `queryDirectToMany` stub to keep the phase-1 behavior, or implement
  native per-parent windows. `PostgresDriver` is `NATIVE`: a direct
  to-many eager edge now executes one `ROW_NUMBER()`-windowed statement
  with the parent keys bound as a single typed array — rows outside a
  finite per-parent window are no longer fetched, parent cardinality no
  longer counts against the 65,535-bind budget, and explain reports the
  step as `EagerWindowStrategy.STORAGE_NATIVE` (a new enum value —
  exhaustive `when`s over `EagerWindowStrategy` gain a branch) with
  `windowOverfetchRisk = false`. The selected rows are identical to
  phase 1's. `FrozenQuerySpec` additionally carries
  `callerPredicateCount` / `structuralPredicateCount` attribution (new
  constructor parameters with backward-compatible defaults).
  _Migration:_ custom drivers implement the two members (`EMULATED` +
  `UnsupportedOperationException` stub is the drop-in choice); Kotlin
  `by`-delegating wrappers forward them automatically but observation
  wrappers that record `query()` calls must also record
  `queryDirectToMany` or direct to-many eager reads disappear from
  their logs; assertions pinning `IN_MEMORY_EMULATED` or overfetch risk
  on direct to-many explain output flip on native drivers.

- **Over-limit PostgreSQL statements fail fast with an actionable error** (`postgres`)
  Operations whose bind-parameter count is data-dependent (`query`,
  `count`, `exists`, `aggregate`, `updateMany`, `deleteMany`) now count
  the rendered statement's final parameters and throw
  `PostgresBindLimitException` before preparing anything over
  PostgreSQL's 65,535-parameter protocol limit — previously the JDBC
  driver failed with an opaque "out-of-range integer as a 2-byte
  value" protocol error. An oversized `IN` list is rejected at render
  time from its projected size — before it is copied or expanded at
  all — and generated read terminals additionally consult the
  driver's declared budget at entry (the new abstract
  `Driver.requireBindCapacity`; drivers with no statement limit
  implement an explicit no-op, and hand-written decorators must
  forward it — it is deliberately not defaulted, like
  `registerAll()`, so a manually-forwarding wrapper cannot silently
  disable the guard) from the lists' O(1) sizes, BEFORE the spec
  builder takes its defensive operand snapshots, so the ordinary
  `client.x.query { ... }.all()` path cannot deep-copy an enormous
  operand on the way to the error either. That entry check runs
  before the interceptor chain — a query that can never execute
  invokes no interceptors. Large eager relationship loads are the
  common trigger; their `IN (...)` lists are not yet chunked.
  `insertMany` and `deleteManyByIds` are unchanged (they already
  chunk).
  The budget is enforced as a running minimum inside the query-spec
  builder, immediately before every operand snapshot — including
  interceptor `addPredicate` contributions — and the typed DSL's
  `column in values` / `notIn` no longer copy their collection at
  construction: operands are snapshotted at terminal entry, after
  the capacity check, matching raw `Predicate.Leaf` construction.
  _Migration:_ custom `Driver` implementations must implement
  `requireBindCapacity` (an empty body for backends with no
  statement limit); none for in-range queries otherwise; code
  matching on the old PSQLException for this condition should match
  `PostgresBindLimitException` instead. Code that mutated a
  collection AFTER passing it to `column in values` and relied on
  the predicate keeping the construction-time contents must copy
  explicitly (`column in values.toList()`); mutations now become
  visible up to terminal entry, as they always were for raw `Leaf`
  construction.
- **Eager many-to-many discovery runs the junction entity's read interceptors** (`codegen`, `runtime`)
  An eager M2M step now fires the JUNCTION entity's interceptors with
  the new `ReadOperation.EAGER_JUNCTION` before its junction read, so
  predicates registered on the junction (tenant scoping,
  `ExcludeDeleted`) narrow relationship discovery exactly as they
  narrow direct junction reads — previously they were silently
  bypassed and `load{Name}()` could expose relationships contributed
  by rows those interceptors hide. `QueryContext.isEagerSubquery` is
  true for the new operation, `QueryPlan` gains a
  `junctionAnnotations` field, and the eager junction explain now
  shows post-interceptor predicates. Junction LOAD privacy still does
  not run on discovery, and the M2M `queryX()` / `has {}` lowerings
  still bypass junction interceptors (open RFC phases).
  _Migration:_ apps with interceptors registered on junction entities
  get them applied to eager loading — results can shrink and a
  rejecting junction interceptor now fails eager reads; exhaustive
  `when (context.operation)` branches need an `EAGER_JUNCTION` arm.
- **Schema names that shadow Kotlin default imports are rejected** (`codegen`)
  An entity class named `Int`, `Any`, `MutableSet`, `Regex`, … would
  shadow the Kotlin declaration wherever generated code references it
  as a bare, unimported name (a same-package declaration outranks
  default imports), making the generated sources uncompilable — in
  files that may never otherwise mention the entity.
  `SchemaInspector.validate` and `EntGenerator.generate` now reject
  such names with a clear diagnostic instead of shipping code that
  fails to compile.
  _Migration:_ rename any schema class whose simple name matches a
  Kotlin default-import declaration used by generated code.
- **Interceptor inputs are snapshotted; contexts and child queries can't be corrupted mid-flight** (`codegen`, `runtime`)
  Query specs now take a semantic snapshot of predicate and ordering
  operands at interceptor-chain entry (and at `addPredicate` time),
  and every `QueryShape` view hands out detached copies — mutating a
  retained or shape-exposed operand (an IN list, a ByteArray) no
  longer changes what executes. `QueryContext.path` is an
  unmodifiable snapshot (mutation attempts throw). An eager step's
  traversal seeding is scoped to the step: a captured `load{Name}`
  child query executed independently later behaves as the fresh root
  query it was constructed as, and eager windows and explain
  metadata read the frozen spec rather than live query state.
  _Migration:_ none for well-behaved code; anything that relied on
  mutating operands after handing them to the framework must apply
  changes through the query DSL or interceptor scope instead.
- **Nested eager loads execute set-based — one pass per edge step, not per parent group** (`codegen`, `runtime`)
  Phase 1 of the [set-based eager graph loader](../possible-features/query/set-based-eager-graph-loader.md)
  replaced grouped per-parent nested recursion: a nested eager edge under a
  hasMany / hasOne / manyToMany step now runs its `EAGER_LOAD` interceptor
  pass, driver query, LOAD-privacy batch, and deeper recursion exactly once
  for the ordered distinct union of every parent group's retained targets.
  Result values are unchanged, but callback observables are not: a grouped
  nested interceptor fires once per logical edge step (its structural `IN`
  holds the complete match set for the edge shape, not one group's), nested
  privacy rules receive one union batch instead of one batch per group, and
  item-level callback and first-denial order follow that union.
  _Migration:_ interceptors and privacy rules on nested eager paths must
  describe the eager query as a whole — any logic that relied on
  per-parent-group invocation counts, per-group batch membership, or
  per-group failure order needs to read the union batch instead.
- **Eager queries execute a deterministic effective order; shape views gain authored-order attribution** (`codegen`, `runtime`)
  Every eager target query now orders by the caller's `orderBy` terms plus
  the target primary key ascending (skipped when the caller already ordered
  by the primary key), computed before the `EAGER_LOAD` interceptor pass.
  Tied rows therefore enter finite per-parent windows deterministically, and
  an otherwise-unordered eager query gains a database sort.
  `QueryShape.orderBy` / `UntypedQueryShape.hasOrderBy` describe that
  effective order — `hasOrderBy` is now true for every eager shape — and the
  new `QueryShape.callerOrderBy` / `hasCallerOrderBy` and
  `UntypedQueryShape.hasCallerOrderBy` fields carry the caller-authored
  attribution. `QueryPlan` gains a framework-owned `eagerExecution` field
  (`EagerExecutionPlan`) reporting set-batched execution, the effective
  order, and the in-memory window-emulation strategy per eager path.
  _Migration:_ policies that require caller-authored ordering must switch
  from `hasOrderBy` to `hasCallerOrderBy`; code constructing `QueryShape` /
  `UntypedQueryShape` directly must pass the new fields; callers relying on
  driver-default row order inside eager windows now get primary-key order.
- **Select edge loads with `load{Name}` instead of `with{Name}`** (`codegen`, `runtime`)
  Generated eager-loading methods are renamed from `with{Name}` to
  `load{Name}` (`withPosts` → `loadPosts`), and the public handle they return
  is renamed from `EagerLoad<ParentQuery>` to `EdgeLoad<ParentQuery>`;
  `filterVisible()` is unchanged. The name states the operation — include the
  relationship in the materialized graph — without promising a SQL strategy.
  Configuration is also stricter, throwing the new
  `EntQueryConfigurationException` instead of silently proceeding: selecting
  one edge twice no longer last-write-wins (including re-entrant selection
  from inside the configuration block, which rolls back on failure); raw
  count / existence / aggregate terminals and their `explain*` variants no
  longer silently ignore a selected graph; `query{Name}` traversal rejects a
  source query with selected edge loads; and `load{Name}` / `filterVisible()`
  are rejected while a terminal or entity explain is executing anywhere in
  the query's selected graph — root and nested target queries alike — so a
  captured query cannot change an in-flight operation.
  Execution, `EdgeState`, and eager LOAD privacy
  semantics are unchanged. See the
  [generated edge loading API note](../implemented-features/query/generated-edge-loading-api.md).
  _Migration:_ rename `with{Name}` calls to `load{Name}` (nested blocks
  included) and `EagerLoad` type references to `EdgeLoad`; merge duplicate
  `load{Name}` calls for one edge into a single block; move `load{Name}`
  selection off queries that end in raw/aggregate terminals; select edge
  loads on the traversal target instead of the traversal source.

- **Namespace the Gradle plugin IDs under `io.entkt`** (`gradle-plugin`)
  The public plugin IDs now align with the verified Maven group and project
  domain: `entkt` is now `io.entkt`, and `entkt.flyway` is now
  `io.entkt.flyway`. Extension names, task names, dependency coordinates, and
  Kotlin packages are unchanged.
  _Migration:_ replace `id("entkt")` with `id("io.entkt")` and
  `id("entkt.flyway")` with `id("io.entkt.flyway")`.

- **Separate shared rule state from item state and correlate batch decisions explicitly** (`runtime`, `codegen`)
  Privacy and validation callbacks now receive phase-wide state separately
  from an item-only generated value. Scalar rules take `(context, item)` and
  batch rules take `(context, batch)`, so the captured read client and privacy
  viewer are no longer repeated in every item. `BatchPrivacyRule` and
  `BatchValidationRule` receive an immutable `RuleBatch<Item>` and return
  read-only, batch-bound `RuleDecisions<D>`. Rules cannot directly construct a
  free positional result, and the decision block receives each original item
  explicitly. This works for ID-less creates and duplicate inputs. Batch hooks
  remain `List`-based because they return `Unit` and their phase-specific shared
  capabilities are not uniform.
  _Migration:_ change scalar callbacks from `{ itemContext -> ... }` to
  `{ context, item -> ... }`; read `context.client` and, for privacy,
  `context.privacy`, while entity/candidate/patch fields move to `item`.
  Generated operation types are renamed from `*PrivacyContext` and
  `*ValidationContext` to `*PrivacyItem` and `*ValidationItem`. Change batch
  callbacks from `{ contexts -> ... }` to `{ context, batch -> ... }` and build
  results with `batch.decideEach { item -> decision }`. Use
  `batch.decideEachIndexed { index, item -> decision }` when equal items need
  distinct handling. `PrivacyRule`, `ValidationRule`, and their batch
  counterparts now take separate `Client` and `Item` type parameters. No
  deprecated callback or `decide` aliases are retained. `RuleBatch.from(items)`
  and the read-only list view of `RuleDecisions` support direct decision tests
  when paired with the rule's matching shared context. Decorators must use
  `result.mapDecisions { ... }` so provenance survives transformation. Do not
  cache or reuse a `RuleDecisions` wrapper across invocations; foreign-batch
  results and malformed Java/unchecked null results fail with
  `EntBatchRuleContractException`.

- **Make lifecycle callbacks batch-aware and bulk execution phase-major** (`runtime`, `codegen`, `postgres`)
  Scalar `PrivacyRule`, `ValidationRule`, and hook call sites keep their
  existing syntax and adapt over ordered batches automatically; optimized
  callbacks can use `batchPrivacyRule`, `batchValidationRule`, and `batchHook`
  under the same generated lifecycle names. Collection and eager LOAD privacy
  now evaluate rule-major batches with one privacy context per logical
  operation. `createMany()` completes every pre-write phase before one logical
  `insertMany()`, hydrates the complete result before hook-major `afterCreate`,
  and then applies returned LOAD privacy. `deleteMany()` completes batch
  privacy, validation, and `beforeDelete` before one logical ID-returning
  delete, reasserting the frozen caller-plus-interceptor predicates; only rows
  actually removed reach `afterDelete`. PostgreSQL statement counts and shapes
  therefore change. See the
  [batch-aware lifecycle design](../possible-features/privacy-validation/batch-aware-lifecycle-evaluation.md)
  for the full ordering and transaction contract.
  Custom drivers are source-broken: `Driver.registeredIdColumn(table)` is a new
  abstract method. `Driver.deleteManyByIds(...)` has a correct default that
  calls `deleteMany()` once per distinct ID; drivers may override it for a
  set-based returning delete. Drivers that advertise typed JSON must also
  override the new default `copyJsonValue(...)` lifecycle-snapshot operation;
  its non-null default fails explicitly. No generated lifecycle-aware
  `updateMany()` was added.
  _Migration:_ after applying the callback-shape migration above, audit code
  that depends on row-major cross-item callback order or on earlier
  `createMany` rows being visible to later pre-write callbacks; use scalar
  terminals in an explicit transaction when that ordering is required. Use the
  explicit batch factories for set-based callback reads. Custom drivers must
  retain registered schema metadata and implement `registeredIdColumn`; audit
  driver-call-count assertions, implement `copyJsonValue` when supporting typed
  JSON, and optionally override `deleteManyByIds` for performance.

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
