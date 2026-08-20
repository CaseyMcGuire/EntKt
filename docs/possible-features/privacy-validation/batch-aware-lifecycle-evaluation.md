# RFC: Batch-Aware Lifecycle Evaluation

## Status

Implemented (2026-08). Scalar callbacks adapt to the batch contracts, explicit
batch factories register under the existing lifecycle names, collection LOAD
privacy is batched, and `createMany()` / `deleteMany()` use the phase-major
pipelines described below. `createMany()` remains available on repositories
with framework- or database-generated IDs; this RFC does not add a new bulk
signature for explicit-ID repositories, whose scalar create API requires an ID
argument.

One source-compatibility consequence is intentional: `registeredIdColumn` is a
new abstract `Driver` method. Existing custom driver implementations must add
it even when they inherit the default `deleteManyByIds` implementation.
Privacy and validation callbacks also use the shared-context/item signatures
described below: shared viewer/client state is passed once, while generated
item types contain only per-item state. Explicit batch-rule implementations
return a `RuleDecisions` value through `decideEach` rather than a free
positional list.

The motivation below describes the pre-implementation baseline.

This RFC supersedes the design direction in
[`preflighted-bulk-operations.md`](preflighted-bulk-operations.md). That note
predates the current atomic bulk-mutation contract and deliberately excludes
batch-aware lifecycle callbacks, so it cannot address the N+1 behavior this RFC
targets.

## Summary

Make privacy rules, entity validators, and lifecycle hooks batch-capable without
creating parallel singular and batch registration APIs.

The central type relationship is:

```kotlin
public sealed interface RuleBatch<out Item> : List<Item> {
    public fun <D> decideEach(block: (Item) -> D): RuleDecisions<D>
    public fun <D> decideEachIndexed(
        block: (index: Int, item: Item) -> D,
    ): RuleDecisions<D>

    public companion object {
        public fun <Item> from(items: List<Item>): RuleBatch<Item>
    }
}

public sealed interface RuleDecisions<out D> : List<D> {
    public fun <R> mapDecisions(
        transform: (D) -> R,
    ): RuleDecisions<R>
}

public class ValidationRuleContext<out Client>(
    public val client: Client,
)

public interface BatchValidationRule<in Client, in Item> {
    public fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision>
}

public fun interface ValidationRule<in Client, in Item> :
    BatchValidationRule<Client, Item> {

    public fun validate(
        context: ValidationRuleContext<Client>,
        item: Item,
    ): ValidationDecision

    override fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision> =
        batch.decideEach { validate(context, it) }
}
```

A scalar rule is therefore already a valid batch rule. Its default
implementation evaluates each item in order. An explicitly batch-aware rule can
instead inspect all items, issue set-based reads, and use `batch.decideEach` to
build exactly one decision per supplied item. The returned `RuleDecisions` is
read-only and bound to that exact input batch. This eliminates direct arbitrary
positional-list returns; application code remains responsible for computing
the right decision for the item supplied to the decision block.

Privacy uses the same shared-context/item relationship. Hooks still receive a
read-only `List` because they return `Unit` and have no output to correlate.
Generated policy and hook DSLs keep one method per lifecycle phase (`load`,
`create`, `beforeCreate`, and so on). They do not add `loadBatch`,
`createBatch`, or `beforeCreateBatch` variants to the Kotlin source API.
Generated batch overloads use distinct `@JvmName` spellings such as
`loadBatchRule` and `beforeCreateBatchHook` so Java lambdas cannot silently
bind to the wrong scalar-or-batch overload.

Generated reads and bulk mutations gather the items that reach a lifecycle
phase and invoke every registered callback through its batch contract. This
allows:

- `all()` and eager loading to evaluate an optimized LOAD rule once for all
  materialized entities;
- `createMany()` to prepare all candidates, evaluate lifecycle callbacks in
  batches, and use `Driver.insertMany()`;
- `deleteMany()` to select candidates once, evaluate lifecycle callbacks in
  batches, and delete approved candidates with a returning set-based driver
  operation; and
- future generated lifecycle-aware update-many APIs to use the same callback
  contracts rather than inventing another extension surface.

This is explicit batching, not automatic query rewriting. A scalar rule that
queries once per item still performs N queries. Applications remove that N+1 by
registering a batch-aware implementation that makes a set-based query.

## Motivation

Before this implementation, EntKt batched some storage access while evaluating
lifecycle code per entity:

- `all()` executes one root query, materializes the result list, and invokes
  LOAD privacy separately for every entity;
- eager loading batches edge storage queries but invokes LOAD privacy separately
  for every eager target;
- `createMany()` delegates to the scalar create pipeline for every input, which
  produces one insert and one set of privacy, validation, and hook invocations
  per row; and
- `deleteMany()` selects candidates with one intercepted query, then delegates
  to the scalar delete pipeline for every selected entity.

The callback invocation count is not itself necessarily expensive. The N+1
appears when a rule, validator, or hook performs a database query:

```kotlin
PrivacyRule<EntPrivacyReadClient, PostLoadPrivacyItem> { context, item ->
    val membership = context.client.projectMemberships
        .query {
            where(
                ProjectMembership.projectId eq item.entity.projectId,
                ProjectMembership.userId eq context.privacy.userIdOrNull(),
            )
        }
        .firstOrNull()
        .getOrThrow()

    if (membership != null) PrivacyDecision.Allow
    else PrivacyDecision.Deny("not a project member")
}
```

Loading 100 posts can make 101 storage queries: one for the posts and one
membership query per post. The framework cannot safely infer that 100 arbitrary
Kotlin callbacks are equivalent to one `IN` query. The callback may branch,
call external code, depend on ordering, or issue unrelated query shapes.

Applications need an explicit extension point that receives all applicable
items and can express the set-based operation directly.

## Goals

- Keep one registration surface per lifecycle phase.
- Preserve concise scalar rules and hooks.
- Let optimized callbacks evaluate every applicable item with set-based reads.
- Preserve privacy rule ordering, per-item short-circuiting, and fail-closed
  behavior.
- Preserve validation's evaluate-all-rules behavior.
- Preserve hook registration order.
- Capture one privacy context for one logical operation.
- Retain strict collection privacy and existing result-algebra behavior.
- Make existing generated bulk mutations use set-based driver writes where the
  driver can report the rows actually affected.
- Keep physical driver chunking invisible to lifecycle callbacks.

## Non-Goals

- Do not automatically rewrite arbitrary callback queries.
- Do not execute lifecycle callbacks concurrently.
- Do not add process-global or cross-request caching.
- Do not replace database constraints with preflight validation.
- Do not change query interceptors into per-entity callbacks. They already run
  once per query shape.
- Do not make a privacy denial silently filter a strict `all()` or bulk
  mutation. Existing explicit filtering and nondisclosure policies remain
  separate.
- Do not make low-level driver bulk methods run generated lifecycle callbacks.
- Do not add a generated lifecycle-aware `updateMany()` API in this RFC. This
  RFC defines the callback contract that such an API must use when it is
  designed.
- Do not solve application-level GraphQL or resolver N+1 behavior. Request-
  scoped entity loading remains a separate feature.

## Terminology

### Logical operation

One generated terminal invocation, such as one `all()`, `createMany()`, or
`deleteMany()` call.

### Item

One typed lifecycle context within the logical operation: a materialized entity,
a write candidate, an update context, or a hook value.

### Batch callback

A callback invoked once with every item that reaches that registered callback.
Privacy and validation receive a `RuleBatch`; hooks receive a read-only `List`.
Either input may contain one item for a scalar operation.

### Physical batch

One driver statement or driver-managed chunk. A logical operation may require
multiple physical batches because of parameter limits. Physical batching must
not split lifecycle callback invocation.

## Public Rule Contracts

Privacy and validation share two sealed runtime types:

```kotlin
public sealed interface RuleBatch<out Item> : List<Item> {
    public fun <D> decideEach(block: (Item) -> D): RuleDecisions<D>
    public fun <D> decideEachIndexed(
        block: (index: Int, item: Item) -> D,
    ): RuleDecisions<D>

    public companion object {
        public fun <Item> from(items: List<Item>): RuleBatch<Item>
    }
}

public sealed interface RuleDecisions<out D> : List<D> {
    public fun <R> mapDecisions(
        transform: (D) -> R,
    ): RuleDecisions<R>
}
```

`RuleBatch` is an immutable, read-only `List`, so rules can inspect, group, or
sort items while preparing a set-based lookup. They return decisions only
through `decideEach` or `decideEachIndexed`; both evaluate against the original
batch order and produce a read-only result bound to that batch.
`decideEachIndexed`
exposes a stable index within the current callback invocation when duplicate or
equal items need distinct handling. Later privacy rules can receive a
filtered batch, so the index is not operation-global. Neither API depends on
entity IDs, so it also works for CREATE candidates whose IDs have not been
generated.

`RuleBatch.from(items)` copies a list for direct tests and rule composition.
`RuleDecisions` exposes ordinary read-only list inspection, equality, and
folding without exposing arbitrary construction. Decorators transform a
delegated result with `mapDecisions`, which preserves its original batch token;
copying delegated indexed values through the current batch would erase the
very stale-result check this type provides. A result created from a test batch
is still foreign to every framework-created callback batch.

The contract prevents direct short, long, or independently reordered list
returns. It cannot prove that a rule's own lookup or iterator associated the
semantically correct value with the item passed to `decideEach`; that remains
application responsibility.

### Privacy

Shared rule state is represented once, separately from generated per-item
values:

```kotlin
public class PrivacyRuleContext<out Client>(
    public val privacy: PrivacyContext,
    public val client: Client,
)

public interface BatchPrivacyRule<in Client, in Item> {
    public fun runBatch(
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision>
}

public fun interface PrivacyRule<in Client, in Item> :
    BatchPrivacyRule<Client, Item> {

    public fun run(
        context: PrivacyRuleContext<Client>,
        item: Item,
    ): PrivacyDecision

    override fun runBatch(
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<PrivacyDecision> =
        batch.decideEach { run(context, it) }
}
```

The same generated item type is used for scalar and batch callbacks. Shared
viewer/client state is never repeated in those items. For example:

```kotlin
public data class PostLoadPrivacyItem(
    public val entity: Post,
)
```

The batch method is deliberately named `runBatch` rather than overloading
`run`. Besides making the invocation shape explicit to implementers, the
distinct JVM name avoids an accidental-override collision for a generic rule
whose `Item` is itself a `List<*>`. The generated lifecycle items are not list
types, but the public runtime interface should remain safe for every legal type
argument and for Java callers.

`PrivacyRule` remains a `fun interface`, preserving concise scalar rule
construction while making the two inputs explicit:

```kotlin
val allowOwner: PostLoadPrivacyRule = PrivacyRule { context, item ->
    if (item.entity.ownerId == context.privacy.userIdOrNull()) {
        PrivacyDecision.Allow
    } else {
        PrivacyDecision.Continue
    }
}
```

`BatchPrivacyRule` is an ordinary interface rather than a `fun interface`.
EntKt provides a factory so batch construction is explicit and cannot make a
lambda ambiguously mean either one context or a list:

```kotlin
public fun <Client, Item> batchPrivacyRule(
    block: (
        context: PrivacyRuleContext<Client>,
        batch: RuleBatch<Item>,
    ) -> RuleDecisions<PrivacyDecision>,
): BatchPrivacyRule<Client, Item>
```

Example:

```kotlin
val allowProjectMembers: PostLoadBatchPrivacyRule =
    batchPrivacyRule { context, batch ->
        val projectIds = batch.map { it.entity.projectId }.distinct()
        val userId = context.privacy.userIdOrNull()

        val visibleProjectIds = context.client.projectMemberships
            .query {
                where(
                    ProjectMembership.projectId.inList(projectIds),
                    ProjectMembership.userId eq userId,
                )
            }
            .all()
            .getOrThrow()
            .mapTo(mutableSetOf()) { it.projectId }

        batch.decideEach { item ->
            if (item.entity.projectId in visibleProjectIds) {
                PrivacyDecision.Allow
            } else {
                PrivacyDecision.Deny("not a project member")
            }
        }
    }
```

The example is a design sketch; generated predicate spelling continues to
follow the query DSL.

### Validation

Validation uses the same relationship while preserving its existing
`validate` method and decision type:

```kotlin
public class ValidationRuleContext<out Client>(
    public val client: Client,
)

public interface BatchValidationRule<in Client, in Item> {
    public fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision>
}

public fun interface ValidationRule<in Client, in Item> :
    BatchValidationRule<Client, Item> {

    public fun validate(
        context: ValidationRuleContext<Client>,
        item: Item,
    ): ValidationDecision

    override fun validateBatch(
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ): RuleDecisions<ValidationDecision> =
        batch.decideEach { validate(context, it) }
}

public fun <Client, Item> batchValidationRule(
    block: (
        context: ValidationRuleContext<Client>,
        batch: RuleBatch<Item>,
    ) -> RuleDecisions<ValidationDecision>,
): BatchValidationRule<Client, Item>
```

Validation returns `ValidationDecision`, not `PrivacyDecision`. Every reached
validation rule still runs; there is no validation equivalent to privacy's
`Continue` short-circuit.

### Hooks

Hooks use the same scalar adapter pattern but return `Unit`. They intentionally
keep an immutable `List` input because no per-item result needs correlation:

```kotlin
public interface BatchHook<in T> {
    public fun runBatch(elements: List<T>)
}

public fun interface Hook<in T> : BatchHook<T> {
    public fun run(element: T)

    override fun runBatch(elements: List<T>) {
        elements.forEach { run(it) }
    }
}

public fun <T> batchHook(
    block: (List<T>) -> Unit,
): BatchHook<T>
```

Generated scalar hook lambdas retain their current concise syntax:

```kotlin
beforeCreate { ctx ->
    ctx.mutation.createdAt = clock.now()
}
```

An optimized hook is explicit:

```kotlin
beforeCreate(
    batchHook { contexts ->
        val accountIds = contexts.map { it.mutation.accountId }.distinct()
        val accounts = loadAccounts(contexts.first().client, accountIds)
        contexts.forEach { applyAccountDefaults(it.mutation, accounts) }
    },
)
```

The generated hook DSL adapts ordinary function literals to `Hook<T>` and
stores both forms in one ordered `MutableList<BatchHook<T>>`.

## Generated Registration API

Each operation retains one name:

```kotlin
privacy {
    load(allowOwner)
    load(allowProjectMembers)
}

validation {
    create(requireTitle)
    create(uniqueSlugsForOperation)
}
```

There is no `loadBatch`, `createBatch`, or `beforeCreateBatch` method in the
Kotlin source API. On the JVM, the batch overloads have explicit names such as
`loadBatchRule`, `createBatchRule`, and `beforeCreateBatchHook`; this preserves
unambiguous Java call sites without adding a second Kotlin DSL vocabulary.

Generated scopes preserve the existing scalar vararg overload and add one
single-rule overload for the broader batch type:

```kotlin
public fun load(vararg rules: PostLoadPrivacyRule) {
    config.loadRules.addAll(rules)
}

public fun load(
    rule: PostLoadBatchPrivacyRule,
) {
    config.loadRules.add(rule)
}
```

The same shape is generated for write privacy and validation. Existing scalar
calls—including `load(*scalarRuleArray)`—keep the exact overload they use
today. An optimized callback is constructed with the explicit batch factory and
goes through the single-rule batch overload. Both overloads append to the same
`MutableList<BatchPrivacyRule<Client, Item>>` or
`MutableList<BatchValidationRule<Client, Item>>` registry.

Generating both `vararg PrivacyRule<Client, Item>` and
`vararg BatchPrivacyRule<Client, Item>` was considered and rejected: it makes zero-argument
calls ambiguous, complicates lambda overload resolution, and widening the
existing scalar vararg would break spread calls because Kotlin arrays are
invariant. The scalar-vararg plus single-batch overload avoids all three
problems. Multiple batch rules are registered with repeated calls, which also
makes their order explicit.

Hooks retain their existing single function-type overload and add a single
`BatchHook<T>` overload. The scalar overload wraps the function in a `Hook<T>`
before appending it. Both forms therefore still enter the same ordered
batch-hook registry.

Generated compile tests must pin all of these forms:

```kotlin
load(allowAll)
load(PostLoadPrivacyRule { _, _ -> /* scalar */ PrivacyDecision.Allow })
load(*scalarRuleArray)
load(batchPrivacyRule { _, batch ->
    batch.decideEach { PrivacyDecision.Allow }
})

beforeCreate { ctx -> /* scalar */ }
beforeCreate(batchHook { contexts -> /* batch */ })
```

Repeated calls append in call order, exactly as repeated scalar registrations
do today. Scalar and batch callbacks therefore share one registration order;
they do not occupy separate phase lists.

Generated operation-specific aliases should expose both forms:

```kotlin
typealias PostLoadPrivacyRule =
    PrivacyRule<EntPrivacyReadClient, PostLoadPrivacyItem>
typealias PostLoadBatchPrivacyRule =
    BatchPrivacyRule<EntPrivacyReadClient, PostLoadPrivacyItem>

typealias PostCreateValidationRule =
    ValidationRule<EntValidationReadClient, PostCreateValidationItem>
typealias PostCreateBatchValidationRule =
    BatchValidationRule<EntValidationReadClient, PostCreateValidationItem>
```

Hooks do not gain generated aliases in this RFC because the current hook API
does not expose scalar aliases. Reusable hooks can use `Hook<Context>` or
`BatchHook<Context>` directly; inline hooks normally rely on the generated
registration method's type inference.

## Shared Batch Contracts

### Non-empty invocation

EntKt never invokes a lifecycle callback with an empty input. If no item
reaches a phase, the phase is skipped.

This preserves existing behavior for empty reads and empty bulk calls: rules
and hooks do not fire merely because a terminal was invoked.

This rule does not suppress query-interceptor passes. Configured eager
subqueries still run their existing interceptor pass even when the root or a
parent group is empty; there is simply no target list on which to invoke LOAD
privacy callbacks.

### Stable order

Every callback receives items in the logical operation's encounter order:

- root query order for `all()`;
- eager-query encounter order for an eager target group;
- caller input order for `createMany()`; and
- candidate-query encounter order for `deleteMany()`.

"Encounter order" does not add an implicit SQL ordering guarantee. If a query
has no `orderBy`, its database result order remains unspecified across
executions. The guarantee here is that EntKt does not reorder the rows after the
driver returns them, and that every lifecycle phase within that logical
operation uses the same captured order.

When an operation needs stable error attribution after filtering an active
privacy set, generated internal records retain the original operation index.
The public callback receives an item-only generated type; shared privacy and
client state is supplied separately through the rule context.

### Correlated rule results

Privacy and validation results are bound to the exact `RuleBatch` supplied to
the callback. Rules cannot construct `RuleDecisions` from an arbitrary list;
they call `batch.decideEach { ... }` or
`batch.decideEachIndexed { index, item -> ... }`.
Both methods invoke the decision block exactly once per item in encounter
order, even when the rule sorted or grouped items while preparing its
lookup. The rule must use the supplied item when selecting its decision;
batch provenance cannot validate application-level lookup semantics.

Entity IDs are not correlation keys:

- CREATE items may not have an ID yet;
- duplicate caller input must not collapse decisions; and
- correlation must work for every configured ID strategy.

The originating-batch check prevents cached decisions from a previous
invocation from being reused accidentally. EntKt raises
`EntBatchRuleContractException` when a rule returns decisions from another
batch. Java or unchecked code receives the same failure if it returns `null`
instead of `RuleDecisions`, or if a `decideEach` callback returns a null or
invalid decision element:

```kotlin
public class EntBatchRuleContractException(
    public val lifecycle: String,
    public val expectedSize: Int,
    public val actualSize: Int?,
    public val invalidDecisionIndex: Int? = null,
    public val foreignBatchResult: Boolean = false,
) : EntException(...)
```

The public API cannot directly produce a short, long, or independently
reordered result container. The size fields remain diagnostic for malformed
binary/unchecked inputs and the framework's defensive boundary checks.

The surrounding read or mutation terminal captures it through the existing
result algebra. Reads store `EntBatchRuleContractException` directly in
`ReadResult.Failed`. Mutations preserve the sealed mutation-failure algebra by
storing `EntUnexpectedMutationException(currentWriteState, contractException)`:
a pre-write contract failure is `NotPersisted`, while a post-write
returned-LOAD contract failure uses the write state already established by the
mutation.

### Fresh rule snapshots

Privacy and validation give each rule fresh immutable item snapshots.
`ByteArray` values are copied directly, while typed JSON values are detached by
`Driver.copyJsonValue()` through the driver's configured mapper so nested
mutable collections and arrays do not alias pending writes. Batch evaluation
preserves that guarantee:

- the framework constructs a fresh ordered `RuleBatch` for each registered
  rule;
- item snapshots within one batch are distinct per item;
- one `PrivacyRuleContext` or `ValidationRuleContext` carries the phase-shared
  read client, and privacy context carries the exact captured viewer value; and
- application mutation of one rule's snapshot cannot change persistence or a
  later rule's inputs.

Update edge-change items also rebuild every `Set` as a detached,
JVM-unmodifiable value. Hook-facing pending-edge sets are unmodifiable as well;
Kotlin's read-only `Set` interface alone is insufficient because a JVM caller
can otherwise cast an ordinary `toSet()` result back to `MutableSet`.

Rules read shared privacy/client state directly from their rule context. They
read each entity, candidate, patch, or edge-change value from the corresponding
generated item. No callback needs `batch.first()` to recover shared metadata.

Hooks are intentionally different. Before-hooks mutate the pending builders,
and later hooks see the effects of earlier hooks according to the documented
hook phase order.

### No concurrent callback execution

EntKt invokes callbacks serially in registration order. A scalar rule's default
batch adapter and a scalar hook's default list adapter also process items
serially in encounter order.

The framework does not launch per-item coroutines or run callbacks in parallel.
This avoids changing callback side-effect ordering and avoids concurrent use of
a transaction-bound driver connection. Performance comes from set-based work
inside batch callbacks and set-based driver writes, not from parallel JDBC
calls.

## Privacy Evaluation

Privacy remains ordered, short-circuiting, and fail-closed independently for
each item.

`Viewer.PrivacyBypass` preserves its current fast path: every item is treated as
allowed and no registered privacy rule—scalar or batch—is invoked. Validation,
hooks, interceptors, and database constraints still run.

For a list of input items, the engine evaluates:

```text
privacyRuleContext = one phase-shared PrivacyRuleContext
active = every item, in encounter order

for each registered rule, in registration order:
    batch = fresh RuleBatch for active items
    decisions = rule.runBatch(privacyRuleContext, batch)
    require decisions originated from batch

    for each active item and corresponding decision:
        Allow    -> finalize that item as allowed
        Deny     -> finalize that item as denied
        Continue -> retain that item for the next rule

items still active after the final rule -> deny fail-closed
```

A later rule never receives an item already allowed or denied by an earlier
rule. Batch rules can therefore avoid querying for items whose decision is
already known.

Evaluation is rule-major across items. This is an intentional observable change
from the pre-implementation scalar item-major loops. For example, rule 1 may
run for item 2 before rule 2 denies item 1. If rule 1 throws while evaluating
item 2, that operational exception is the phase result; EntKt does not continue
to discover the later rule-2 denial for item 1.

An explicitly batch-aware rule can also throw only at the batch-call boundary,
without attributing the exception to one item. Exceptions therefore take
registration-step precedence, while returned decisions retain per-item
encounter ordering. Privacy rules are trusted read-only policy code and should
not use cross-item invocation order for external side effects.

Rule derivation preserves its current order. UPDATE or DELETE rules run first;
derived CREATE rules receive only items still active after the operation's own
rules.

The operation consumes the per-item outcomes according to its existing public
contract:

- strict `all()` aggregates every denied root row in query order;
- strict eager loading reports the existing eager-edge denial shape;
- `createMany()` and `deleteMany()` fail on any denied item and do not silently
  omit it; and
- where the current mutation API reports one row denial, the first denied item
  in encounter order remains the reported failure.

Batch evaluation does not turn privacy into filtering. Explicit
`visibleOrNull()` and eager `filterVisible()` behavior remains unchanged.

## Validation Evaluation

Every reached validation rule runs for every applicable item:

```text
validationRuleContext = one phase-shared ValidationRuleContext
for each registered validation rule, in registration order:
    batch = fresh RuleBatch for every item
    decisions = rule.validateBatch(validationRuleContext, batch)
    require decisions originated from batch
    append every Invalid decision to that item's violations
```

Rules remain rule-major during a batch: rule one receives all items, then rule
two receives all items. For each individual item, rule order and violation order
remain registration order.

A thrown validator exception stops the phase at that registered rule and takes
precedence over returned `Invalid` decisions accumulated from earlier rules.
This matches the existing rule-thrown-versus-returned distinction, but the
rule-major batch order can change which later-item exception is encountered
before which earlier-item invalid result.

Bulk mutation error projection stays minimal in this RFC. If several items are
invalid, the bulk terminal reports the first invalid item in encounter order
using the existing `EntValidationException` payload and that item's collected
violations. This RFC does not introduce a partial result or a new bulk-error
algebra.

Validation derivation preserves its current order: UPDATE rules run before
derived CREATE rules, and every reached rule contributes violations.

## Hook Evaluation

Hooks remain grouped by their existing lifecycle phase:

- `beforeSave` before `beforeCreate` or `beforeUpdate`;
- write privacy and entity validation before `beforeDelete`;
- after-hooks only after a successful database statement; and
- returned LOAD privacy after after-hooks.

Within one hook kind, registration order remains authoritative. Each registered
hook is invoked through `BatchHook.runBatch(elements)`:

- an ordinary `Hook<T>` uses the default implementation and visits elements in
  encounter order; and
- a `BatchHook<T>` implementation runs once with the full list.

This changes cross-item interleaving for existing bulk operations. Given two
scalar hooks `A` and `B`, the new order is:

```text
A(item 1), A(item 2), B(item 1), B(item 2)
```

The prior scalar-delegating implementation completed all hooks and the write
for item 1 before starting item 2. Exact row-major interleaving is incompatible
with invoking a batch hook once and issuing one set-based write. The generated
bulk API therefore adopts phase-major semantics.

Within each item, `A` still precedes `B`. Applications that genuinely require
the complete scalar pipeline for one item before the next can loop over scalar
mutation terminals inside an explicit transaction.

Before-create and before-update contexts retain their current write-capable
`EntClientScope`. A hook can therefore issue other repository mutations before
the target operation reaches privacy or validation. In this RFC, "fail before
persistence" means before EntKt issues the logical operation's target-row
insert, update, or delete statement; it does not claim that arbitrary hook code
performed no database work.

Hook-issued mutations use the same transaction and are rolled back with an
EntKt-owned bulk operation after a confirmed failure. In a caller-owned
transaction, the failure marks the scope rollback-only. External side effects
performed by hooks remain non-transactional, exactly as today; batching does not
make them reversible.

## Read Semantics

### Root reads

Materializing read terminals use one captured privacy context and invoke LOAD
rules with the materialized root list:

```text
read interceptors once
database query once
materialize root entities
evaluate LOAD privacy over the root list
load requested eager edges
return ReadResult
```

`findById()` and `firstOrNull()` use the same engine with a singleton
`RuleBatch` when an entity exists. They do not invoke LOAD privacy when the
database result is absent.

An optimized rule can turn this:

```text
1 root query + N membership queries
```

into:

```text
1 root query + 1 set-based membership query
```

EntKt guarantees one invocation of that batch rule for the root result list. It
does not guarantee that application code inside the rule issues only one query.

### Eager loads

Each eager edge preserves the current deduplication and ordering contract before
calling the batch evaluator:

- direct to-one targets are evaluated in target-query order;
- grouped has-one and has-many targets are evaluated once across the complete
  eager query, after per-parent windows are applied, in target-query order; and
- a shared many-to-many target is evaluated once rather than once per parent.

The resulting ordered list contains only targets present in at least one
parent's requested window. Scalar rules use their default mapping; optimized
rules see that list once. Strict eager loading still reports the first denied
target in this order, while `filterVisible()` removes every denied target from
all parent groups that reference it.

Nested eager loads repeat the same behavior once per logical edge step: since
the set-based eager executor shipped, a grouped eager path's nested edges
recurse once for the ordered distinct union of retained targets (including one
empty pass when nothing was retained), so each nested edge contributes exactly
one privacy batch. The privacy context captured by the root terminal is
threaded unchanged through every step.

### Interceptors

Read interceptors already operate once per query shape. This RFC does not add a
`BatchQueryInterceptor` type.

Queries issued inside a batch rule still run their ordinary interceptor chain.
Because an optimized rule normally issues one set-based query, its interceptors
also run once for that query rather than once per lifecycle item.

## Create-Many Semantics

This section applies to repositories that expose `createMany()` (all current
non-`EXPLICIT` ID strategies). Explicit-ID repositories retain
`create(id) { ... }`; designing a bulk input that carries one ID per block is a
separate API decision, not an implicit vararg convention in this RFC.

`createMany()` becomes a phase-oriented logical operation:

```text
1. check the transaction requirement against the caller's transaction posture
2. if no blocks were supplied, return Success(emptyList())
3. instantiate every create builder in caller input order
4. run beforeSave hooks over all builders
5. run beforeCreate hooks over all builders
6. apply defaults, required checks, and field validation to every builder
7. build every immutable WriteCandidate
8. capture one privacy context
9. evaluate CREATE privacy over all candidates
10. if any candidate is denied, fail before persistence
11. evaluate CREATE entity validation over all candidates
12. if any candidate is invalid, fail before persistence
13. call Driver.insertMany with every prepared row
14. hydrate persisted entities in input order
15. run afterCreate hooks over all persisted entities
16. evaluate returned LOAD privacy over all entities
17. return the complete list or one failure
```

The whole operation retains its current transaction boundary. Framework-owned
`createMany()` opens one transaction; transaction-client `createMany()` uses the
caller's transaction and marks it rollback-only on a pre-completion failure.
The transaction-requirement check remains ahead of builder blocks and every
other observable callback, and uses `multiWrite = blocks.size > 1` exactly as
today. The empty-input return therefore remains after that preflight but before
transaction creation or lifecycle work. For non-empty input without a caller
transaction, EntKt enters its owned transaction before step 3; builder blocks,
hooks, callback queries, persistence, and returned disclosure all use that
transaction-bound client.

Each builder created for `createMany()` is permanently owned by that logical
batch. Calling `save()`, `saveAndLoad()`, or the internal save seam on such a
builder returns `Failed(EntUnexpectedMutationException(NotPersisted, ...))`
before lifecycle callbacks or I/O, including through a reference captured by a
different configuration block. The enclosing `createMany()` observes the
attempt and fails even if the block ignores that scalar result. This prevents
the same builder from being persisted once during configuration and again by
the phase-major batch pipeline. A configuration block may deliberately launch
a separate repository mutation through its transaction-bound client; that is
an independent logical operation with its own lifecycle/context capture and is
not part of the createMany callback batch.

Failure precedence is phase-major. In particular, CREATE privacy completes for
the entire candidate list before entity validation begins. A privacy denial on
a later input therefore takes precedence over an entity-validation failure on
an earlier input. This preserves the security ordering that validation details
are not produced until the complete write-privacy phase has allowed the logical
operation.

The existing `Driver.insertMany()` input-to-result correlation guarantee is
required. Generated code must never correlate returned rows by unspecified SQL
result order. It also validates that the driver returned exactly one row per
prepared input before hydrating any entity or invoking any after-hook. A
cardinality mismatch raises a clearly messaged driver-contract failure inside
the active transaction; it can never produce a partial success list.

The driver may physically chunk the insert for parameter limits, but all chunks
remain within the same transaction and lifecycle callbacks still receive the
full logical operation.

### Visibility after persistence

The current returned-disclosure contract remains:

- inside a caller-owned transaction, a returned LOAD denial or failure marks
  the transaction rollback-only and reports `TransactionPending`; and
- for an EntKt-owned batch, returned LOAD evaluation runs against the staged
  rows inside the transaction. A returned denial or ordinary failure is
  captured rather than thrown so the transaction can attempt to commit. A
  confirmed commit reports that disclosure failure with `Committed`; if the
  disclosure work has aborted the database transaction, a confirmed rollback
  reports `NotPersisted`; and an uncertain transaction boundary reports
  `PersistenceUnknown`.

Batch returned-LOAD evaluation must preserve those write-state classifications.

## Delete-Many Semantics

`deleteMany()` becomes:

```text
1. check the transaction requirement against the caller's transaction posture
2. capture one privacy context
3. run DELETE_CANDIDATES read interceptors once
4. query matching candidate entities once
5. build immutable delete candidates
6. evaluate DELETE privacy over all candidates
7. if any candidate is denied, fail before persistence
8. evaluate DELETE entity validation over all candidates
9. if any candidate is invalid, fail before persistence
10. run beforeDelete hooks over all candidates
11. delete the candidate IDs, reasserting the effective selection predicates,
    with one ID-returning driver operation
12. extract the IDs actually deleted
13. select the corresponding preflight entities in candidate encounter order
14. run afterDelete hooks only for entities actually removed
15. return the deleted count
```

The transaction-requirement preflight remains before the privacy-context
provider, interceptor chain, candidate query, and every callback. It continues
to classify `deleteMany()` as a multi-write shape even when no rows eventually
match, matching the existing contract. Without a caller transaction, EntKt
enters its owned transaction after preflight and before step 2, so selection,
checks, hooks, and deletion share one transaction-bound client.

The driver needs an ID-column metadata lookup and an ID-scoped returning
primitive rather than only the existing predicate/count-returning
`deleteMany`:

```kotlin
public fun registeredIdColumn(table: String): String

public fun deleteManyByIds(
    table: String,
    idColumn: String,
    ids: List<Any>,
    predicates: List<Predicate<*>>,
): List<Any>
```

The driver contract is:

- an empty input performs no statement and returns an empty list;
- every returned ID was supplied by the caller and was actually deleted;
- one deleted row appears at most once even if the input repeats an ID; and
- returned order is unspecified.

The generated repository passes the complete effective predicate list from the
intercepted candidate query in addition to the approved IDs. The write is
therefore equivalent to:

```sql
DELETE FROM posts
WHERE id IN (<approved candidate ids>)
  AND <caller predicates>
  AND <interceptor predicates>
RETURNING id
```

Both parts are required. The ID set prevents a row that only begins matching
after preflight from entering the write without lifecycle evaluation. Predicate
reassertion prevents an approved row that stops matching tenant, soft-delete,
or caller scope before the statement from being deleted anyway.

Generated repositories pass distinct IDs because one candidate query cannot
materialize the same root row twice. The duplicate-input rule still makes the
public low-level driver method complete and consistent across implementations.

The generated repository must pass exactly the IDs selected and approved during
preflight and the frozen effective predicates used to select them. It must not
rerun the interceptor chain or reconstruct only the caller-authored predicates
at the write stage.

The returned IDs need not arrive in input order. Generated code uses them as the
affected-row acknowledgement, then selects the corresponding entities from the
already loaded candidate list in candidate encounter order. Current
`afterDelete` hooks receive the entity loaded before deletion, and batch delete
preserves that snapshot contract.

Before invoking `afterDelete`, generated code verifies that returned IDs are
unique and belong to the approved input set. A driver-contract violation fails
the operation inside its transaction rather than running hooks for an
unapproved or ambiguously acknowledged row.

`Driver` supplies a correctness-preserving `deleteManyByIds` default that
processes each distinct provided ID through the existing predicate-based
`deleteMany`, adding one `idColumn = id` predicate to the supplied effective
predicates for each call, and returns IDs whose statement removed a row. It
validates `idColumn` through the new abstract `registeredIdColumn(table)`
lookup before rendering it. The default limits migration work and preserves
predicate reassertion, but existing `Driver` implementations are not
source-compatible until they implement that metadata lookup. PostgreSQL
overrides the delete with a set-based
`DELETE ... WHERE id IN (...) RETURNING id` implementation, internally chunking
only when required by parameter limits. Other drivers can optimize
independently without gaining a new required capability flag.

This preserves idempotence under a concurrent delete: an entity selected during
preflight but absent by the delete statement is not counted, and its
`afterDelete` hook does not run.

As today, a denied or invalid candidate is not silently skipped. Any such item
fails the entire logical operation before the delete statement.

## Future Generated Update-Many APIs

The public shape of a lifecycle-aware generated `updateMany()` is not settled:

- one shared patch applied to a query;
- a list of ID-specific patches; and
- per-candidate hook mutations have different driver and concurrency needs.

This RFC therefore does not add that generated terminal. It does establish the
requirements it must follow:

- select current entities through read interceptors once;
- build per-item requested patches, effective patches, candidates, and edge
  deltas;
- use batch-capable hooks, UPDATE privacy, and validation;
- fail before persistence when any candidate is denied or invalid;
- use a returning set-based driver primitive capable of representing differing
  per-row values when hooks produce them; and
- run after-hooks only for rows actually changed.

The existing low-level `Driver.updateMany()` remains a hookless,
privacy-unaware primitive and is not changed by this RFC.

## Privacy Context Capture

Every logical operation captures `PrivacyContext` at most once and threads that
exact value through every stage that consumes viewer state:

- read interceptors;
- root and eager LOAD privacy;
- bulk mutation privacy;
- queries made through privacy-rule read clients; and
- returned-entity LOAD privacy.

Capture occurs at the earliest existing privacy-consuming phase, not
unconditionally at terminal entry:

- reads and `deleteMany()` capture before read interceptors, because their query
  shaping consumes the viewer context;
- creates capture after before-hooks, defaults, required checks, and field
  validation but immediately before CREATE privacy, preserving the current
  lifecycle's failure order; and
- updates capture after their current-row load and before UPDATE privacy, at
  the corresponding point in the update lifecycle.

An empty operation or a failure before the first privacy-consuming phase does
not invoke the provider merely to establish a context that will never be used.

Bulk operations no longer invoke the privacy context provider once per scalar
delegation. A provider that changes its viewer between invocations cannot cause
different items in one logical operation to authorize as different viewers.

A repository terminal explicitly started by application hook code is a nested,
separate logical operation and keeps its own normal context-capture boundary.
This RFC does not make one outer mutation snapshot govern arbitrary operations
that a trusted hook chooses to launch before the outer privacy phase.

Validation remains viewer-independent and uses its existing validation read
client posture.

## Failure And Result Semantics

This RFC does not introduce new success or failure variants.

- Callback-thrown exceptions remain foreign application failures and are
  captured at their existing positional boundary.
- Privacy denials retain their typed privacy exception and denial payload.
- Validation failures retain `EntValidationException`.
- Hook failures are classified with the state established at the point of
  failure inside the active transaction. As today, an EntKt-owned bulk
  transaction that subsequently confirms rollback re-reports the final bulk
  failure as `NotPersisted`; a caller-owned transaction retains
  `TransactionPending` and is marked rollback-only.
- Driver failures continue through driver-failure classification.
- Once `Driver.insertMany()` begins for a multi-input logical batch, a thrown
  failure in a caller-owned transaction is conservatively
  `TransactionPending`: a driver may have staged an earlier input's physical
  chunk even when its final statement has a precisely classified
  `NotPersisted` constraint or conflict. That typed statement failure remains
  the cause. A one-input batch retains the typed `NotPersisted` classification,
  and an EntKt-owned batch exposes it only after rollback is confirmed.
- A malformed successful response from `insertMany()` or
  `deleteManyByIds()` is a framework-detected driver contract failure. It is
  stored as `EntUnexpectedMutationException` with the current transaction
  write state, marks a caller-owned transaction rollback-only, and becomes
  `NotPersisted` if an EntKt-owned transaction confirms rollback.
- `CancellationException` and JVM `Error` behavior remains unchanged.
  Atomicity relies on those control-flow failures propagating to the
  transaction boundary; catching and suppressing either inside a transaction
  block is unsupported.
- A foreign-batch, null, or invalid batch-rule result is an operational
  contract failure, not a denial or validation decision.

Bulk methods remain all-or-nothing at the generated API boundary. They never
return partial entity lists or silently omit denied mutation candidates.

## Transaction And Concurrency Semantics

Batch preflight does not make application-level checks race-free. A uniqueness
validator can approve every candidate and still lose to a concurrent insert.
Applications must continue to use database constraints for stored invariants.

Candidate selection and a later set-based update or delete can also race unless
the transaction uses appropriate locking or isolation. Returning driver
operations tell EntKt which rows were actually affected; they do not promise
that every preflight snapshot remained current.

For `deleteMany()`, the driver write reasserts the frozen candidate predicates
as well as the approved ID set. A row that vanishes or stops matching is omitted
from the returned IDs, the success count, and `afterDelete`. That closes the
candidate-scope race, but it does not re-evaluate arbitrary DELETE privacy,
validation, or hook logic against a concurrently changed row that still matches
the predicates. Closing that broader checked-state window still requires the
locking posture described by the Delete Consistency proposal.

This RFC does not add implicit row locks. Any stronger consistency guarantee
belongs to the existing query-locking and delete-consistency design work.

## Performance Contract

For a batch-aware callback, EntKt guarantees invocation shape, not callback
query count:

- once per registered batch rule for the non-empty items that reach it;
- once per registered batch hook for the non-empty phase list; and
- once per logical operation even when the driver physically chunks writes.

Scalar callbacks intentionally retain O(N) invocation through their default
batch/list adapters. This is the compatibility path, not an automatic
optimization.

Generated set-based writes target:

- one logical `Driver.insertMany()` call for `createMany()`; and
- one logical ID-returning delete call for `deleteMany()`.

Driver implementations may chunk internally while preserving transaction and
correlation guarantees.

## Breaking Changes

Implementation added an entry to `docs/breaking-changes/index.md` covering:

1. Bulk lifecycle execution becomes phase-major rather than completing the
   entire scalar pipeline for one item before beginning the next.
2. In `createMany()`, every before-hook, privacy rule, and validator completes
   before the first insert. A later item's callback can no longer observe an
   earlier item already inserted by the same bulk call.
3. Every `afterCreate` callback begins after the set-based insert has persisted
   the complete batch. An early item's after-hook can therefore observe later
   items from the same batch within the transaction.
4. Every returned row is hydrated before the `afterCreate` phase begins. A
   later row's hydration failure therefore prevents earlier `afterCreate`
   callbacks that the prior scalar-delegating implementation may already have
   invoked.
5. Hooks of the same kind retain registration order per item, but cross-item
   order changes from item-major to hook-major.
6. Privacy and validation rules become rule-major across bulk items. A callback
   exception from a later item at an earlier registered rule can therefore
   precede a returned denial or invalid decision from an earlier item at a later
   rule.
7. Bulk privacy completes before bulk entity validation. A later item's privacy
   denial can therefore precede an earlier item's validation failure.
8. Bulk operations capture the privacy context provider once rather than once
   per scalar item.
9. PostgreSQL `createMany()` and `deleteMany()` issue set-based driver writes,
   so driver call counts and SQL statement shapes change. Drivers using the
   default `deleteManyByIds` implementation preserve correctness but
   retain per-row delete calls.
10. Applications implementing optimized rules use the new batch interfaces and
    must create their batch-bound result through the supplied `RuleBatch`'s
    `decideEach` or `decideEachIndexed` method. Batch hooks remain `List`-based.
11. Custom `Driver` implementations must implement the new abstract
   `registeredIdColumn(table)` metadata lookup. They may inherit the correct but
   per-ID `deleteManyByIds()` default or override it with a set-based returning
   delete.
12. Drivers that advertise typed JSON support must override the default
    `copyJsonValue(table, column, value)` snapshot operation. The non-null
    default fails explicitly; a valid implementation returns a detached value
    through the same mapper configuration used for storage.
13. Scalar privacy and validation callbacks now receive two arguments:
    phase-shared `PrivacyRuleContext` / `ValidationRuleContext` and one
    generated item. Shared clients and privacy state no longer appear on every
    generated item.
14. Batch privacy and validation callbacks receive the same shared context plus
    `RuleBatch<Item>`. Rule interfaces therefore gain separate `Client` and
    `Item` type parameters, generated `*Item` types replace the former combined
    callback contexts, and `decide` / `decideIndexed` are replaced by
    `decideEach` / `decideEachIndexed` without aliases.

Hook DSL syntax remains source-compatible. Privacy and validation rule bodies
require the callback migration above; EntKt is pre-stable, so the old callback
signatures and decision-builder names are removed rather than deprecated. The
driver compatibility statement likewise does not apply to custom Driver
implementations because `registeredIdColumn` is abstract.

Applications that rely on exact row-major bulk-hook interleaving should replace
the bulk call with an explicit scalar loop inside `withTransaction`.

## Relationship To Other Proposals

### Preflighted Bulk Operations

This RFC supersedes it. The current runtime already gives generated bulk
mutations one atomic transaction boundary. The missing design is batch-aware
lifecycle evaluation and set-based persistence, not merely moving scalar checks
before scalar writes.

### Request-Scoped Entity Loading

Request-scoped loaders target repeated application-level `findById` and edge
loads. They may deduplicate identical reads but cannot transparently combine
arbitrary synchronous queries issued by per-item lifecycle callbacks.

Batch rules solve that problem explicitly by giving one callback every relevant
item. Both features can coexist.

### Query Interceptors And Visibility Filters

Interceptors already shape one query at a time and are the most efficient place
for policies that genuinely mean "add this predicate to every query." They are
not equivalent to strict LOAD privacy: filtering a row produces absence or
omission, while strict privacy can fail because a selected row was denied.

This RFC preserves that distinction.

### Delete Consistency

This RFC adopts that proposal's unconditional predicate-reassertion rule for
`deleteMany()`. The approved ID set and frozen effective predicates are both
part of the ID-returning write, so set-based persistence does not reopen the
candidate-selection race. The optional pessimistic locking API and the residual
privacy/validation staleness window remain in the separate
[Delete Consistency](../mutation/delete-consistency.md) proposal.

### Structured Mutation Pipeline

A future structured mutation pipeline could give the phases different names or
capabilities. Batch-capable callbacks remain applicable: each named phase can
invoke an ordered list of scalar-adapting or optimized batch callbacks.

## Alternatives Considered

### Separate singular and batch registration methods

Examples: `load` plus `loadBatch`, or `beforeCreate` plus
`beforeCreateBatch`.

Rejected because callers would need to learn whether both lists run, how they
interleave, and which one should contain shared policy. It also doubles the
generated lifecycle vocabulary.

### Make every callback batch-native

This provides one callback shape but makes simple scalar rules and hooks
unnecessarily verbose. The inheritance design keeps one engine contract while
retaining concise scalar authoring.

### Transparent DataLoader-style batching

Current callbacks synchronously execute and consume arbitrary query results.
The first callback needs its result before the framework sees the second
callback's key. Transparent batching would require deferred/suspending query
APIs, callback scheduling, or recording and replaying application code.

That complexity is not justified when an explicit batch rule can issue the
set-based query directly.

### Automatically inspect and rewrite callback queries

Arbitrary Kotlin code is not a declarative query plan. Rewriting it would be
incomplete and could change branching, exception, or side-effect behavior.

### Fast bulk methods that skip lifecycle callbacks

Some ORMs gain bulk performance by skipping model callbacks and validation.
EntKt's privacy and lifecycle guarantees should not depend on callers choosing
the safe terminal. Generated bulk methods continue to run their full lifecycle.

### Parallelize scalar callbacks

Parallel execution does not combine queries, changes ordering, complicates
failure selection, and is unsafe for a transaction-bound JDBC connection.
Set-based queries are the intended optimization.

## Implementation Outline

1. Add the batch privacy, validation, and hook contracts plus explicit batch
   factories to runtime. Privacy and validation use immutable shared rule
   contexts alongside sealed `RuleBatch` and `RuleDecisions` provenance types;
   hooks remain `List`-based. A public copied `RuleBatch.from` factory and
   read-only decision list support direct rule tests without exposing arbitrary
   result construction.
2. Add shared evaluators that verify result provenance and retain original item
   indexes internally.
3. Generate item-only privacy and validation types, and change registries to
   store batch-capable rules parameterized by the shared client and item type
   while preserving scalar registration overloads.
4. Change generated hook registries to store ordered batch-capable hooks while
   preserving ordinary hook lambdas.
5. Extend each generated internal read surface with a correlated batch LOAD
   evaluator, for example
   `loadDenials(privacy, entities): List<PrivacyDenial?>`. Root and eager reads
   call it once per logical target group. Keep `loadDenialOrNull` for singleton
   callers and implement it through the same evaluator so scalar reads and
   returned-mutation disclosure cannot drift from batch semantics.
6. Split generated create execution into preparation, persistence, after-hook,
   and returned-disclosure phases; drive `createMany()` through
   `Driver.insertMany()`.
7. Split generated delete execution into candidate preparation and persistence;
   add the required registered-ID-column lookup, the default
   ID-and-effective-predicate-returning driver primitive, and a set-based
   PostgreSQL override.
8. Preserve scalar mutation terminals by invoking the same phase helpers with
   singleton inputs where doing so does not alter their public behavior.
9. Update lifecycle, privacy, validation, hook, driver, and breaking-change
   documentation.

## Test Requirements

### API and code generation

- Scalar privacy and validation callbacks receive `context, item`; batch
  callbacks receive `context, batch`, and both forms use the same generated
  item type.
- Existing scalar hook trailing lambdas compile unchanged.
- Scalar privacy and validation registration names and vararg/spread behavior
  remain unchanged after migrating callback signatures.
- Explicit batch factories resolve to the single-rule batch overload rather
  than a scalar rule.
- Batch rules register under the existing `load`, `create`, `update`, and
  `delete` names.
- Batch hooks register under the existing hook names.
- Scalar and batch registrations share one stable registration order.
- Generated operation-specific scalar and batch aliases have collision-free
  names, including every new generated `*Item` declaration.

### Shared contracts

- Scalar rules map over batch inputs in encounter order.
- Scalar hooks visit batch inputs in encounter order.
- Batch callbacks are not invoked for empty input.
- Batch rules receive singleton `RuleBatch` values for scalar operations;
  batch hooks receive singleton lists.
- Rule decisions can be constructed only through the supplied batch's
  `decideEach` and `decideEachIndexed` methods.
- Decisions created by a different batch produce
  `EntBatchRuleContractException`.
- A Java or unchecked rule returning null instead of `RuleDecisions` produces
  the same exception.
- A null or otherwise invalid decision element produces the same contract
  exception with its positional index.
- ID-less CREATE items and duplicate or equal inputs retain distinct,
  original-order correlation.
- Every registered privacy or validation rule receives fresh defensive
  snapshots.

### Privacy

- A batch LOAD rule over 100 root rows is invoked once.
- A scalar LOAD rule over 100 root rows is invoked 100 times through its default
  adapter.
- Earlier `Allow` and `Deny` decisions remove only their corresponding items
  from later rules.
- Fail-closed items remaining after the final rule are denied.
- `Viewer.PrivacyBypass` skips scalar and batch privacy rule invocation.
- Strict `all()` aggregates denied rows in root query order.
- A top-level eager edge invokes a batch rule once for its ordered, deduplicated
  in-window target list; shared M2M targets are not evaluated twice.
- Nested eager privacy follows the existing per-`loadEdges` invocation grouping.
- `visibleOrNull()` and eager `filterVisible()` retain their existing
  projections.
- A rule-thrown exception remains an operational failure rather than a returned
  denial.

### Validation

- Every validation rule receives every applicable item.
- Violations for one item remain in rule registration order.
- If several items are invalid, the bulk terminal reports the first invalid
  item in encounter order.
- Validation read queries made by one batch rule can cover every candidate in
  one set-based query.
- Derived validation rules run after operation-specific rules.

### Hooks

- Scalar and batch hooks run in one registration order.
- Hook-major cross-item ordering matches the documented batch contract.
- Mutations made by an earlier before-hook are visible to later hooks and to
  candidate construction.
- A hook failure stops later hook steps and preserves current result/write-state
  classification.
- No callbacks are executed concurrently.

### Reads

- `all()` captures one privacy context and shares it across interceptors, root
  batch privacy, and eager privacy.
- A representative batch privacy rule turns 100 per-row membership reads into
  one set-based membership read.
- Empty reads do not invoke LOAD rules.
- `findById()` and `firstOrNull()` invoke batch rules with one item only when an
  entity exists.

### Create many

- Explicit-ID repositories continue to omit `createMany()`; generated-ID
  repositories retain their existing bulk source shape.
- Transaction-requirement failure happens before builder blocks, context
  capture, hooks, or driver work.
- Empty input still performs transaction-requirement preflight but performs no
  transaction or lifecycle callback work.
- All before-hooks, field checks, CREATE privacy, and entity validation finish
  before `Driver.insertMany()`.
- A denial, invalid decision, or pre-write callback exception causes no insert.
- `Driver.insertMany()` receives prepared rows in caller input order.
- Returned rows correlate to inputs according to the existing driver contract.
- A wrong `insertMany()` result cardinality fails before entity hydration or
  after-hooks and cannot return a partial list.
- After-hooks receive persisted entities after the complete set-based insert.
- Returned LOAD privacy is batch-evaluated.
- A returned-disclosure failure retains current caller-owned versus EntKt-owned
  write-state behavior.
- Driver chunking remains inside one transaction and does not split lifecycle
  callback invocation.

### Delete many

- Transaction-requirement failure happens before privacy-context capture,
  interceptors, queries, or lifecycle callbacks.
- Candidate read interceptors run once.
- DELETE privacy and validation are batch-evaluated before deletion.
- A denied or invalid candidate causes no delete statement.
- Before-hooks complete before the ID-returning delete statement.
- The delete statement combines approved IDs with the exact frozen effective
  caller and interceptor predicates from candidate selection.
- Unknown or duplicate returned IDs fail before `afterDelete` hooks.
- After-hooks run only for IDs returned as actually deleted.
- Preflight entities for the returned IDs are restored to candidate encounter
  order before hooks.
- A concurrent disappearance produces a smaller success count and no
  `afterDelete` call for the missing row.
- A candidate that stops matching a caller or interceptor predicate is not
  deleted, is not counted, and does not reach `afterDelete`.
- A row that begins matching only after candidate selection is not deleted.

### Transactions and failures

- Generated bulk operations remain atomic after a confirmed rollback.
- Batch callback queries use the transaction-bound read client and see earlier
  writes already present before the logical operation.
- Driver failures retain current constraint and outcome-unknown classification.
- Batch callback exceptions keep existing cancellation and JVM-error behavior.
- Transaction rollback-only coordination records the batch-level failure.

## Documentation Requirements

Implementation must update:

- `docs/operation-lifecycle.md` with phase-major bulk diagrams;
- `docs/05-hooks.md` with scalar-adapter and batch-hook examples;
- `docs/06-privacy.md` with batch rule ordering and strict-denial behavior;
- `docs/07-validation.md` with batch validation and correlated-result rules;
- `docs/08-privacy-limitations.md` to remove the per-item privacy-context
  provider limitation;
- `docs/10-drivers.md` with set-based generated bulk usage and ID-returning
  delete; and
- `docs/breaking-changes/index.md` with the observable ordering and driver-call
  changes listed above.
