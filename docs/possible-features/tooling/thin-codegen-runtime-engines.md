# RFC: Thin Codegen And Runtime Execution Engines

## Status

Possible future architectural direction. This is not implemented.

## Summary

EntKt should keep its generated, entity-specific Kotlin API while moving
reusable framework control flow out of generated source and into well-tested
runtime execution engines.

The normative ownership rule is:

> Generated code declares and adapts entity-specific facts. Runtime engines
> exclusively own reusable framework control flow.

A generated operation may validate its typed DSL state, construct an immutable
operation description, and delegate. It must not independently define generic
lifecycle ordering, transaction semantics, graph traversal, driver capability
routing, or failure handling.

The goal is not to generate fewer useful types. `User`, `UserQuery`,
`UserCreate`, `UserUpdate`, typed privacy and validation items, and typed index
helpers remain part of EntKt's public API. Their implementations become thin
shims over reusable engines and generated adapters.

The architecture must preserve compile-time typing and hot-path performance.
It must not replace generated code with reflection, dynamic repositories,
unchecked application-facing casts, or a universal `Map<String, Any?>` engine.

## Motivation

Generated repositories currently contain substantial execution logic for each
entity: query interception, materialization, privacy evaluation, eager loading,
create/update/delete lifecycles, bulk operations, transaction outcome mapping,
and driver failure classification.

For the integration schema, codegen currently emits roughly 49,000 lines of
Kotlin. The generated repositories and query classes alone account for roughly
28,000 lines. Much of that source repeats framework algorithms with different
entity, field, and edge names.

That approach makes each generated file self-contained, but it has costs:

- application compile time, bytecode, and IDE indexing grow with entity count;
- a semantic fix must be emitted correctly into every generated artifact;
- execution algorithms are expressed indirectly as KotlinPoet fragments rather
  than ordinary Kotlin functions;
- lifecycle behavior is difficult to unit-test without generating and compiling
  an entity;
- query and mutation changes mix framework semantics with codegen names,
  imports, collision handling, and source formatting;
- driver and result-contract changes cause broad generated churn;
- stack traces and coverage reports point into repeated generated algorithms;
- optimizations must be reproduced across every generated operation family.

Generated source should describe what is specific to an entity. Runtime code
should implement what is true for every entity.

## Goals

- Preserve EntKt's generated, Kotlin-first public API.
- Have one authoritative implementation of each reusable execution algorithm.
- Make lifecycle, transaction, graph, and failure semantics directly unit-testable.
- Reduce generated source, classfile size, compilation work, and generated
  implementation churn.
- Keep entity adaptation statically typed and free of hot-path reflection.
- Preserve current result, privacy, validation, hook, interceptor, transaction,
  and driver contracts unless another RFC explicitly changes them.
- Make generated code easy to read: typed state, adapters, and delegation rather
  than emitted control-flow machinery.
- Allow runtime engines to evolve independently from application schema size.

## Non-Goals

- Do not replace generated typed APIs with a dynamic repository.
- Do not make application callers use runtime engine types directly.
- Do not move schema correctness checks from generation to first execution.
- Do not use reflection to discover fields, invoke constructors, or attach edges
  on every row.
- Do not make `Any?`, erased maps, or unchecked casts the primary boundary
  between generated shims and runtime engines.
- Do not combine privacy, validation, and hooks into one semantic abstraction
  merely because all three can be batch-shaped.
- Do not combine synchronous JDBC and suspend/R2DBC execution behind one
  ambiguous engine.
- Do not redesign the public query, mutation, edge-loading, privacy, or result
  APIs as part of this refactor.
- Do not require the modular driver SPI refactor before runtime engines can
  delegate through the current `Driver` contract.
- Do not build a general codegen-plugin framework as a side effect.

## Ownership Rule

The boundary is behavioral, not based merely on file size.

Generated code owns a concern when its shape depends on the declared schema or
is necessary to present a statically typed application API. Runtime owns a
concern when its ordering and meaning are the same for every entity.

### Generated Code Owns

- entity data classes and nested `Edges` values;
- typed columns, edge references, index stages, and repository names;
- create/update DSL properties and edge mutators;
- hook-facing mutation interfaces and restricted views;
- typed privacy, validation, hook, patch, candidate, and edge-change values;
- immutable entity, field, edge, index, and relationship metadata;
- row decoding and storage-value encoding adapters;
- entity key accessors;
- entity-specific required-field and type preparation;
- entity-specific default materialization where it cannot be represented by
  shared metadata without losing type safety;
- construction of typed lifecycle items;
- edge attachment and copy functions;
- public return types and overloads;
- generated-member and top-level declaration collision validation;
- narrow graph-wide wiring such as one repository property per entity.

### Runtime Engines Own

- query-spec freezing and generic interceptor-chain execution;
- row-terminal execution and result capture;
- graph-plan traversal and relationship execution;
- native-versus-emulated driver capability selection;
- privacy and validation rule iteration and contract enforcement;
- hook phase iteration;
- create, update, delete, and bulk lifecycle phase ordering;
- transaction ownership and rollback-only coordination;
- mutation write-state transitions;
- cancellation and ordinary-exception boundaries;
- driver failure classification orchestration;
- failure encounter order and suppression rules;
- returned-entity disclosure orchestration;
- generic cardinality and driver-contract checks;
- common bulk-operation algorithms;
- explain-plan traversal and framework-owned execution metadata;
- reusable defensive snapshot mechanics.

### A Generated Method May

- validate an immediate typed API misuse;
- mutate or read generated builder state;
- construct an immutable typed or schema-adapted description;
- call a generated adapter;
- delegate to a runtime engine;
- project a generic engine result into the exact public return type.

### A Generated Method Must Not

- reproduce a generic phase loop;
- decide when privacy runs relative to validation or persistence;
- own a transaction outcome state machine;
- contain generic driver `try/catch` templates;
- implement generic graph recursion;
- select between native and emulated execution strategies;
- reconstruct generic failure precedence;
- duplicate an engine algorithm because one entity has different field names.

## Generated Surface After Migration

The generated file families remain recognizable. The refactor changes their
implementation responsibility, not their public purpose.

| Generated family | Remains generated | Moves to runtime |
| --- | --- | --- |
| `${Entity}.kt` | entity, `Edges`, typed columns/edges, schema metadata, decoder | nothing beyond reusable row access helpers |
| `${Entity}Query.kt` | typed mutable DSL, relationship selection, entity-specific plan adapters, public terminals | terminal capture, interceptor orchestration, driver execution, graph traversal, privacy, explain traversal |
| `${Entity}Repo.kt` | typed entry points, repository property, lifecycle registrations, adapters | find/delete/bulk algorithms, rule orchestration, transaction and failure mapping |
| `${Entity}Create.kt` | typed draft properties, restricted hook views, preparation adapter, public terminals | create phases, driver call, write state, hooks/rules, disclosure, failure mapping |
| `${Entity}Update.kt` | typed patch and edge DSL, restricted views, patch/candidate adapters, public terminals | current-row load, phase ordering, edge resolution/write orchestration, transaction and failure mapping |
| `${Entity}Mutation.kt` | hook-facing mutation interfaces | no generic algorithm belongs here |
| `${Entity}Privacy.kt` | rule aliases, item/candidate/patch/context types, typed registration scopes | registry mechanics, rule evaluation, derived-operation orchestration |
| `${Entity}Validation.kt` | rule aliases, typed items, typed registration scopes | registry mechanics, rule evaluation, derived-operation orchestration |
| `${Entity}Indexes.kt` | staged typed index API and immutable query-plan construction | terminal execution |
| `${Entity}ViewerEntity.kt` | entity-specific dynamic-tool adapter | viewer execution engine |
| `EntClient.kt` | typed repository graph and configuration DSLs | transaction, privacy-scope, lifecycle-copy, and execution coordination |
| `EntReadClient.kt` | typed read-only facades and posture-specific surfaces | common read-client implementation |
| `EntReadRuntime.kt` | generated cross-entity read capability interfaces | common read execution |
| `GeneratedEntViewerRegistry.kt` | static entity-adapter registry | viewer behavior |

Private generated carriers that exist only because control flow is generated,
such as operation-specific transaction/disclosure state machines, should
disappear once the corresponding runtime engine owns that state.

## Runtime Engine Families

Do not replace many generated algorithms with one universal god engine. Use
cohesive engines whose state machines and dependencies are independently
testable.

The exact names below are illustrative; their ownership boundaries are
normative.

### Read Engine

Owns ordinary row terminals:

- capture one privacy context;
- freeze caller and interceptor query state;
- preflight driver requirements;
- invoke the driver;
- decode through the entity adapter;
- evaluate root LOAD privacy;
- capture exceptions into `ReadResult`;
- invoke the graph engine when a selected graph exists.

Generated queries retain their typed builder surface and supply an immutable
query description plus the entity adapter.

### Edge-Load Engine

Owns:

- traversal of the immutable selected graph;
- one logical execution per reached edge step;
- direct and many-to-many relationship plan execution;
- native/emulated strategy selection;
- grouping, per-parent windows, and canonical ordering;
- edge LOAD privacy and `filterVisible` behavior;
- set-based nested recursion;
- shared-target deduplication;
- generated attachment-adapter invocation;
- eager explain metadata.

Phase 1 and phase 2A of the set-based eager loader contain transitional runtime
helpers, but generated query classes still emit much of this orchestration.
This engine should absorb that remaining algorithm rather than introduce a
second graph implementation.

### Create Engine

Owns scalar and bulk create phase ordering:

1. configure typed drafts;
2. run `beforeSave`;
3. run `beforeCreate`;
4. ask the generated adapter to prepare candidates and storage values;
5. evaluate CREATE privacy;
6. evaluate validation;
7. execute scalar or correlated bulk persistence;
8. decode entities;
9. run `afterCreate`;
10. perform returned LOAD disclosure when requested;
11. convert transaction outcomes and failures.

The generated builder owns typed draft state and restricted views. The engine
owns every phase transition.

### Update Engine

Owns:

- transaction/locking requirements;
- current-row loading;
- requested/effective patch phase ordering;
- before hooks;
- generated candidate and edge-change adapter invocation;
- UPDATE privacy and validation;
- owner-row and relationship writes;
- after hooks;
- returned LOAD disclosure;
- write-state and failure conversion.

Generated code owns typed patch state, unset operations, edge mutators, and the
functions that convert that state into immutable generated values.

### Delete Engine

Owns scalar delete, delete-by-id, and delete-many orchestration:

- candidate selection and frozen predicate reuse;
- DELETE privacy and validation;
- acknowledgement correlation;
- disappearance/conflict semantics;
- after hooks;
- caller-owned versus EntKt-owned transaction outcomes;
- partial-progress driver classification.

### Transaction And Failure Engine

This is a shared service used by mutation engines, not another generated
operation family. It owns:

- caller-owned versus engine-owned transaction branches;
- rollback-only coordination;
- confirmed commit, confirmed rollback, and outcome uncertainty;
- `MutationWriteState` transitions;
- first-failure encounter order;
- suppression without losing diagnostics;
- cancellation propagation;
- typed conversion of driver, callback, privacy, and validation failures.

Mutation engines report phase events to this service rather than manually
constructing nested `try/catch` trees.

## Typed Adapter Design

The runtime module cannot refer to application-generated classes. Generated
code therefore supplies statically typed adapters across a small
`@EntktInternal` SPI.

### Entity Adapter

Conceptually:

```kotlin
@EntktInternal
interface EntityAdapter<Entity : Any, ID : Any> {
    val descriptor: EntityDescriptor
    fun key(entity: Entity): ID
    fun decode(row: RowView): Entity
}
```

`EntityDescriptor` contains validated runtime metadata. `RowView` centralizes
storage lookup and diagnostics; generated `decode` remains direct Kotlin code.

The adapter must not reflect over constructor parameters or fields.

### Create Adapter

Conceptually:

```kotlin
@EntktInternal
interface CreateAdapter<Builder, Candidate, Entity : Any, ID : Any> {
    val entity: EntityAdapter<Entity, ID>

    fun configure(builder: Builder, block: Builder.() -> Unit)
    fun beforeSaveView(builder: Builder): Any
    fun beforeCreateItem(builder: Builder, services: HookServices): Any
    fun prepare(builder: Builder): MutationResult<PreparedCreate<Candidate>>
    fun createPrivacyItem(candidate: Candidate): Any
    fun createValidationItem(candidate: Candidate): Any
}
```

The final interfaces should split hook/privacy/validation adaptation into
typed subcontracts rather than standardize on `Any`. The erased values above
only illustrate that the engine coordinates entity-specific types it cannot
name. The accepted design must preserve the types through generic parameters
or captured typed adapter objects and localize unavoidable erasure inside one
audited runtime node.

### Update Adapter

Supplies typed operations for:

- immutable requested and effective patches;
- hook-facing mutation views;
- candidate construction;
- edge-change snapshots;
- owner storage values;
- relationship write plans;
- decoding and attachment.

It does not choose the order in which those operations run.

### Edge Adapter

Conceptually:

```kotlin
@EntktInternal
interface ToManyEdgeAdapter<Source : Any, SourceID : Any, Target : Any> {
    val relationship: RelationshipDescriptor
    fun sourceKey(source: Source): SourceID
    fun targetKey(target: Target): Any
    fun attach(source: Source, targets: List<Target>): Source
}
```

To-one and many-to-many variants carry the corresponding typed attachment and
association metadata. Heterogeneous graph nodes necessarily erase some types
when stored in one graph, but each node must capture a typed adapter at
construction. Erasure stays inside the runtime graph node; generated and
application call sites remain type-safe.

### Adapter Constraints

- Adapters are immutable objects or immutable captured functions.
- Engines must not discover adapters through reflection or global registries.
- Generated code passes adapters explicitly.
- Entity metadata is resolved and validated during generation/client setup.
- Hot paths do not allocate a new adapter per row or lifecycle item.
- An adapter cannot reorder phases; it implements only named adaptation steps.
- Adapter failures retain entity, field, edge, and operation diagnostics.
- Runtime interfaces used by generated application modules are public and
  guarded by `@EntktInternal`; Kotlin `internal` alone cannot cross the module
  boundary.
- Keep the cross-module SPI deliberately small. Generated convenience helpers
  that are used only inside one emitted file remain private/internal there.

## Illustrative Generated Code

The target is ordinary, readable delegation.

```kotlin
public class UserCreate internal constructor(
    private val draft: UserCreateDraft,
    private val engine: CreateEngine,
) : UserCreateMutationView {

    public var name: String?
        get() = draft.name
        set(value) { draft.name = value }

    public var email: String?
        get() = draft.email
        set(value) { draft.email = value }

    public fun save(): MutationResult<Unit> =
        engine.save(UserCreateAdapter, draft)

    public fun saveAndLoad(): MutationResult<User> =
        engine.saveAndLoad(UserCreateAdapter, draft)
}
```

The generated adapter contains only entity-specific facts:

```kotlin
@OptIn(EntktInternal::class)
internal object UserCreateAdapter :
    CreateAdapter<UserCreateDraft, UserWriteCandidate, User, Long> {

    override val entity = UserEntityAdapter

    override fun prepare(
        draft: UserCreateDraft,
    ): MutationResult<PreparedCreate<UserWriteCandidate>> {
        val name = draft.name ?: return requiredFieldFailure("User", "name")
        val email = draft.email ?: return requiredFieldFailure("User", "email")
        val candidate = UserWriteCandidate(name, email, draft.apiToken)
        return MutationResult.Success(
            PreparedCreate(
                values = mapOf(
                    "name" to name,
                    "email" to email,
                    "api_token" to draft.apiToken,
                ),
                candidate = candidate,
            ),
        )
    }
}
```

The runtime engine contains the reusable control flow as normal Kotlin:

```kotlin
@EntktInternal
class CreateEngine internal constructor(
    private val execution: MutationExecutionServices,
) {
    fun <Builder, Candidate, Entity : Any, ID : Any> saveAndLoad(
        adapter: CreateAdapter<Builder, Candidate, Entity, ID>,
        builder: Builder,
    ): MutationResult<Entity> = execution.captureCreate(adapter.entity.descriptor) {
        runBeforeHooks(adapter, builder)
        val prepared = adapter.prepare(builder).getOrReturnFailure()
        authorizeCreate(adapter, prepared.candidate)
        validateCreate(adapter, prepared.candidate)
        val row = persistCreate(adapter.entity.descriptor, prepared.values)
        val entity = adapter.entity.decode(row)
        runAfterCreate(adapter, entity)
        discloseLoad(adapter.entity, entity)
    }
}
```

The example omits detailed result algebra. The real engine must preserve the
existing non-throwing terminal boundaries, cancellation behavior, transaction
coordinator recording, failure identity, and write-state semantics.

## Immutable Operation Descriptions

Engines consume immutable descriptions once terminal execution begins.

- A query terminal freezes predicates, ordering, bounds, interceptor output,
  privacy posture, and the selected edge graph.
- A create terminal snapshots the relevant generated draft through its adapter.
- An update terminal snapshots requested field and edge operations before
  lifecycle evaluation.
- A bulk terminal snapshots block order and owns one logical batch identity.

Callbacks may mutate generated hook views only at documented phases. They
cannot retain and mutate the engine's canonical immutable plans.

This is not permission to create one untyped universal AST for every operation.
Descriptions should be specific enough to retain useful static types and
explicit enough that engines do not infer phase meaning from arbitrary maps.

## State And Lifetime

- Generated query and mutation builders remain mutable configuration objects.
- Builders are not made thread-safe by this refactor.
- A terminal freezes one execution snapshot; subsequent builder mutation
  cannot change the in-flight operation.
- Runtime engines are immutable/stateless where possible and may be shared by
  repositories.
- Per-operation mutable state lives in an execution object created for that
  terminal, never on a singleton engine.
- Transaction-scoped services cannot escape their transaction lifetime.
- Engines must not retain generated builders, privacy contexts, or clients
  after execution completes.

## Result And Failure Compatibility

This refactor must not subtly normalize existing result behavior.

Parity includes:

- the exact `ReadResult`, `MutationResult`, and `TransactionResult` variant;
- typed exception class, operation, entity key, reason, and violations;
- `MutationWriteState` at every returned boundary;
- cancellation rethrow behavior;
- first-failure encounter order;
- suppressed exception identity and ordering;
- caller-owned rollback-only recording;
- owned-transaction commit/rollback/uncertainty conversion;
- strict versus `filterVisible` eager privacy behavior;
- empty-batch callback behavior;
- rule and hook registration order;
- driver call order and data gates;
- returned LOAD disclosure timing.

Runtime engines should make these contracts easier to see by representing
phase and outcome state explicitly, not by reproducing generated nested
`try/catch` structures.

## No Reflection Or Dynamic Hot Path

The runtime engine must not discover fields or invoke entity constructors
through reflection for every row. Codegen supplies direct decoders, encoders,
key accessors, candidate builders, lifecycle-item factories, and edge
attachment functions.

Metadata may use erased internal collections at the driver boundary because
drivers operate on heterogeneous schema graphs. That erasure must not leak into
the generated public API or become the primary engine-to-adapter contract.

Permitted localized erasure must be:

- hidden behind `@EntktInternal`;
- established by a typed generated constructor/factory;
- checked once when the immutable plan is built where practical;
- free of repeated reflective lookup;
- covered by a focused runtime contract test.

## Performance Requirements

This refactor is justified only if it improves maintainability without
regressing execution.

Before the first migration, record a reproducible baseline for:

- generated source lines and files per entity;
- generated classfile count and byte size;
- clean application Kotlin compilation time;
- incremental compilation after one schema change;
- allocations per decoded row;
- simple query throughput;
- eager graph throughput;
- scalar and bulk create/update latency;
- application startup and client-construction time.

Each migrated slice must demonstrate:

- a material reduction in generated implementation source for that slice;
- no extra database statements or rows fetched unless another RFC requires it;
- no per-row reflection;
- no adapter allocation per row;
- no meaningful throughput or allocation regression;
- smaller or equal generated bytecode for the migrated operation.

Generic engines should use frozen immutable plans and precomputed adapters so
they do not trade compile-time duplication for runtime interpretation overhead.

## Testing Strategy

### Runtime Engine Tests

Test engines directly using small fake adapters and event-recording drivers.

For mutations, table-driven tests should cover combinations of:

- lifecycle phase;
- caller-owned versus engine-owned transaction;
- pre-write, pending, committed, and uncertain write states;
- callback, privacy, validation, codec, and driver failures;
- commit, rollback, and uncertain transaction outcomes;
- cancellation;
- multiple recorded failures and suppression.

For reads and graph loading, tests should cover:

- interceptor order and frozen inputs;
- native/emulated strategy parity;
- statement/data gating;
- privacy ordering and keyed denials;
- shared-target deduplication;
- attachment identity;
- nested execution order;
- explain metadata.

### Generated Compile Tests

Generated tests should primarily prove:

- public Kotlin and Java call-site types;
- overload resolution;
- field, ID, candidate, patch, and edge typing;
- visibility and `@EntktInternal` boundaries;
- member-name collision handling;
- adapters implement the expected runtime SPI.

Avoid making large generated-source substring assertions the primary proof of
runtime semantics.

### Migration Parity Tests

While an old generated algorithm and a new runtime engine coexist, run both
against the same deterministic fixture and compare:

- returned values;
- exact result/failure structure;
- event trace;
- driver calls and arguments;
- transaction outcomes;
- lifecycle callback inputs and order.

The old path is test-only during migration, not a permanent runtime flag.

### Driver Tests

Engines continue to call the driver SPI. Driver conformance tests remain
responsible for storage semantics; engine tests prove operation orchestration.

## Migration Plan

Do not rewrite every generated surface at once. Every phase must be
independently reviewable and shippable while preserving the public API.

### Phase 0: Baseline And Contracts

1. Capture generated-source, classfile, compile-time, allocation, and throughput
   baselines.
2. Inventory semantic event traces for current read and mutation terminals.
3. Define the minimal public `@EntktInternal` adapter SPI and compatibility
   rules.
4. Add fake-adapter and trace-test infrastructure.

### Phase 1: Root Row Terminals

1. Introduce `EntityAdapter`/`RowView` and a read engine for `all()` and
   `firstOrNull()` without selected edges.
2. Generate a thin entity adapter and terminal delegation.
3. Keep caller DSL and public result types unchanged.
4. Run old/new parity and performance tests.
5. Remove the migrated root-terminal control-flow emitter after parity passes.

This is deliberately narrower than eager graph loading and mutation state.

### Phase 2: Graph Loading And Explain

1. Move selected-graph traversal into the edge-load engine.
2. Reuse the existing set-based eager-load plans and phase-2A driver operation;
   do not create a parallel graph representation.
3. Move grouping, windows, privacy, recursion, attachment orchestration, and
   eager explain traversal out of generated query classes.
4. Retain generated typed edge-plan and attachment adapters.
5. Delete duplicated eager emitters only after native/emulated parity tests pass.

### Phase 3: Scalar Create

1. Introduce typed create adapters and the shared transaction/failure service.
2. Move scalar create phase ordering and returned LOAD disclosure.
3. Preserve generated draft properties and mutation views.
4. Prove exact write-state, failure-identity, cancellation, and callback order.

### Phase 4: Bulk Create

1. Reuse the scalar create adapter in the bulk engine.
2. Move phase-major configuration, hooks, privacy, validation, correlated
   persistence, hydration, and disclosure.
3. Preserve createMany-managed builder misuse detection and failure encounter
   order.

### Phase 5: Update

1. Introduce update/patch/edge adapters.
2. Move current-row loading, mutation phases, relationship writes, and
   disclosure.
3. Preserve locking and transaction requirements.

### Phase 6: Delete Operations

1. Move scalar delete and delete-by-id.
2. Move candidate-based `deleteMany` with frozen predicate reassertion and
   acknowledgement correlation.
3. Share transaction/failure machinery with create/update.

### Phase 7: Client And Registry Simplification

1. Move privacy/transaction-scoped client coordination into runtime services.
2. Replace repeated per-entity registry mechanics with generic registries behind
   generated typed scopes.
3. Keep generated repository properties and configuration DSLs.
4. Remove generated private state carriers and helpers no longer referenced.

### Phase 8: Remove Compatibility Emitters

1. Remove test-only old algorithms after every parity gate passes.
2. Re-baseline generated size and compilation performance.
3. Update architecture and contributor documentation.
4. Move this RFC to implemented features only when every public operation family
   delegates through runtime engines and no generic lifecycle/graph algorithm
   remains generated.

## Rollout Rules

- Migrate one terminal family at a time.
- Do not mix a public API redesign into an engine extraction.
- Do not retain two production implementations selected by a runtime flag.
- A slice is not complete while generated code still contains the generic
  algorithm under a differently named helper.
- Remove old emitters only after semantic and performance parity.
- If a proposed adapter requires pervasive `Any?` or reflection, stop and
  redesign the boundary rather than accepting hidden type debt.
- If an entity has genuinely distinct semantics, model that as explicit typed
  metadata or a narrow adapter capability; do not fork the whole algorithm.

## Relationship To Driver Refactoring

Runtime engines may initially delegate through the current `Driver` interface.
The [Modular Driver SPI](modular-driver-spi.md) can later replace those calls
with structured commands and cohesive capabilities.

The dependency direction should be:

```text
generated public shim
        ↓
generated typed adapter
        ↓
runtime execution engine
        ↓
driver SPI / capability
        ↓
database driver
```

Generated code must not branch on PostgreSQL-specific details. Runtime engines
select a declared capability; drivers own physical lowering.

## Relationship To Codegen Plugins

Runtime engines make extension points more constrained. Codegen plugins should
contribute metadata, typed adapters, or declared lifecycle/query stages rather
than arbitrary copies of generated execution algorithms.

A plugin must not replace an engine's failure, transaction, privacy, or
validation semantics by injecting uncontrolled control flow.

## Documentation Requirements

When implementation begins, contributor documentation must explain:

- the generated/runtime ownership rule;
- the adapter SPI and cross-module visibility convention;
- how to add a schema-specific fact without adding a generated algorithm;
- how to add or change an engine phase;
- where engine, codegen compile, integration, and driver tests belong;
- how to inspect generated-source and performance baselines.

Generated KDoc should describe public behavior, not expose runtime engine class
names as a stable application contract.

## Completion Criteria

This RFC is complete only when:

- generated query, repository, create, and update terminals are thin delegates;
- reusable graph and lifecycle phase loops live in runtime engines;
- transaction/write-state/failure mapping has one implementation;
- generated privacy/validation scopes delegate to generic registry mechanics;
- row decoding, candidate construction, typed mutation views, and edge
  attachment remain generated without hot-path reflection;
- no generic execution algorithm is copied per entity;
- public Kotlin and Java API compile fixtures remain valid;
- parity tests cover results, failures, event order, driver calls, and
  transaction outcomes;
- generated source and classfile size decrease materially;
- benchmarks show no meaningful hot-path regression;
- runtime engine stack traces retain useful entity/field/edge diagnostics;
- driver-specific behavior remains behind the driver SPI.

## Resolved Decisions

- Preserve generated public entity, query, repository, create, update, policy,
  validation, index, and viewer types.
- Move reusable framework control flow to runtime engines.
- Use generated typed adapters, not reflection, to bridge runtime and generated
  application types.
- Keep unavoidable type erasure localized inside audited internal plan nodes.
- Use several cohesive engines rather than one universal operation engine.
- Preserve existing semantics during migration; public API changes require
  separate RFCs.
- Begin with root row terminals, then graph loading, then mutation families.
- Keep the modular driver SPI complementary rather than prerequisite.

## Open Decisions

- Final adapter and engine names.
- Whether entity adapters are one composite object or a small set of capability
  objects assembled into an immutable descriptor.
- The exact typed representation for heterogeneous edge-plan nodes.
- Whether `RowView` replaces raw row maps at the driver boundary immediately or
  first adapts existing maps inside runtime.
- The exact benchmark thresholds for a meaningful regression.
- Whether runtime/generated SPI compatibility needs an explicit version marker
  before independent artifact evolution makes mismatches plausible.

## Related Features

- [Set-Based Eager Graph Loader](../query/set-based-eager-graph-loader.md)
- [Structured Mutation Pipeline](../mutation/structured-mutation-pipeline.md)
- [Modular Driver SPI](modular-driver-spi.md)
- [Driver Capability Matrix](driver-capability-matrix.md)
- [Codegen Plugin Hooks](codegen-plugin-hooks.md)
- [Same-Module Schema Processing](same-module-schema-processing.md)
