# RFC: Canonical Operation Result Algebra

## Status

Implemented (2026-08). This design is the breaking successor to the
previous [EntKt Result Variants](../tooling/entkt-result-variants-rfc.md)
design: one canonical result-bearing operation per family instead of
generated terminal variants. The consolidated migration entry lives in
the [breaking-changes log](../../breaking-changes/index.md).

## Summary

Make every public generated data operation return a canonical exhaustive
result. Query-explanation diagnostics remain outside the algebra.
Throwing and nullable behavior become reusable Kotlin projections of
that result rather than separately generated database operations.

For reads:

```kotlin
sealed interface ReadResult<out T> {
    data class Success<T>(val value: T) : ReadResult<T>

    @ConsistentCopyVisibility
    data class Failed internal constructor(
        val exception: Exception,
    ) : ReadResult<Nothing>

    companion object {
        @EntktInternal
        fun failedForInternalUse(
            exception: Exception,
        ): ReadResult<Nothing> = Failed(exception)
    }
}
```

The success payload carries the operation's cardinality. A singular entity
lookup uses nullable `T` to distinguish presence from authoritative absence. A
collection uses non-null `List<T>`, so `Success(null)` is not a valid
collection result. SQL aggregates that naturally produce `NULL` use a nullable
scalar payload; there, null means the aggregate's documented SQL-null result,
not entity absence. `Failed` implements `ReadResult<Nothing>` through
covariance. LOAD denial is `Failed(EntPrivacyDeniedException(...))`; other read
failures store the original or framework-created typed exception.

Canonical reads return that union:

```kotlin
repo.findById(id): ReadResult<User?>
query.firstOrNull(): ReadResult<User?>
query.all(): ReadResult<List<User>>
```

Callers choose a projection without causing another database operation:

```kotlin
repo.findById(id).getOrThrow() // User?; null only for absence
```

Callers may explicitly treat LOAD denial as absence without performing another
query:

```kotlin
users.findById(id).visibleOrNull().getOrThrow()
```

Mutations use a distinct, flat result algebra and expose two terminals:

```kotlin
builder.save(): MutationResult<Unit>
builder.saveAndLoad(): MutationResult<Entity>
```

`save()` reports whether the mutation completed but does not disclose a
returned entity. `saveAndLoad()` additionally materializes and applies LOAD
privacy to the entity it returns. Both results provide a `getOrThrow()`
projection. Mutation results deliberately provide no `orNull()` projection
because null cannot safely collapse absence, rejection, committed failure, and
unknown write state.

## Motivation

The implemented result-variants API generates parallel terminals such as:

```kotlin
byIdOrThrow(id)
byIdOrNull(id)
byIdOrError(id)
visibleByIdOrNull(id)
```

and equivalent variants for query and mutation operations. Those methods are
useful, but the surface has several costs:

- result representation and database execution posture are encoded together
  in method names
- code generation repeats the same projection logic for many operations
- a caller can mistake two terminals for different database behaviors when
  they should differ only in Kotlin-side error handling
- adding a new operation multiplies the number of generated terminals
- nullable and throwing APIs discard distinctions that the framework already
  knows
- writes do not fit naturally into the same success/absence model as reads

An exhaustive result makes the lossless behavior the default. Convenience is
still available, but it is an explicit projection at the call site.

## Design Principles

1. **The canonical operation is lossless.** It preserves successful values,
   authoritative absence, typed failure reasons, and mutation write state.
2. **A projection does not perform I/O.** `getOrThrow()` and `visibleOrNull()`
   operate only on an already-produced result.
3. **Privacy-as-absence is an explicit transformation.** `visibleOrNull()`
   changes only the result representation; it performs no I/O and never scans
   beyond the selected SQL window.
4. **Read and mutation states are not forced into one artificial union.**
5. **Canonical terminals capture ordinary exceptions.** Once a result-bearing
   read, mutation, or transaction terminal begins, every ordinary `Exception`
   that crosses that boundary becomes its corresponding `Failed` result. The
   original exception is stored directly for reads and transactions or
   preserved as the cause of `EntUnexpectedMutationException`; callers do not
   construct `Failed` themselves. Cancellation and JVM errors still propagate.
6. **A mutation result must describe the commit boundary honestly.** It must
   never imply that a failed write did not happen when it may have committed.

## Goals

- Give reads and writes one canonical, exhaustive API each.
- Make read absence and every failure reason distinguishable, and preserve each
  mutation failure's typed exception and write state.
- Remove generated `OrError`, `OrThrow`, and nullable operation variants where
  generic projections can provide the same behavior.
- Keep strict SQL-window reads predictable.
- Preserve post-write facts even when the saved entity cannot be returned.
- Support idiomatic exhaustive `when` expressions.
- Reduce generated API size and documentation burden.

## Non-Goals

- Do not use `kotlin.Result`; its general `getOrNull()` projection would
  silently collapse read failure into absence, and it cannot attach mutation
  write state.
- Do not define bulk-operation partial-success semantics, implicit skipping, or
  implicit conflict handling in this RFC. Canonical `createMany()` and
  `deleteMany()` are strict and atomic; a future partial-success operation
  requires a separately named contract.
- Do not define privacy-skipping scans or visible-page filling. Those may be
  added later if concrete application requirements justify the additional
  ordering, cursor, and scan-budget contracts.
- Do not define HTTP, GraphQL, or other untrusted-boundary mappings for privacy
  denials.
- Do not add `map()`, `flatMap()`, `fold()`, or other general result-composition
  helpers. Callers can use exhaustive `when`; narrowly useful transformations
  may be added later with operation-specific semantics.
- Do not add or define an `afterCommit` callback API. EntKt's existing
  post-persist lifecycle hooks remain in scope; a future callback that runs
  specifically after commit belongs to the Structured Mutation Pipeline design
  and must integrate with `MutationWriteState` when that feature is specified.
- Do not move query-explanation diagnostics into `ReadResult`; `explain*()`
  retains its `QueryPlan` contract, including rejected plans.
- Do not settle cursor and visible-page representation here; the separate
  [Privacy-Aware Visible Pagination](../../possible-features/query/privacy-aware-visible-pagination.md)
  RFC remains independent future exploration.

## Canonical Generated Surface

The canonical algebra applies to generated repository, query, mutation, and
client transaction terminals. Ordinary low-level `Driver` read and write
methods continue to return values or throw, while generated terminal boundaries
translate those outcomes into the appropriate result. The transaction-outcome
and exception-classification portions of the public `Driver` SPI change as
defined below because generated code cannot infer commit certainty from a bare
exception. Builder and DSL methods that configure an operation are not
terminals and continue to validate arguments by throwing immediately.

The source-breaking migration covers every current generated terminal family,
with the explicit query-explanation exception shown below:

| Operation family | Canonical surface |
|---|---|
| primary-key lookup | `findById(id): ReadResult<Entity?>` |
| completed unique-index helper | `find(): ReadResult<Entity?>` |
| first query row | `firstOrNull(): ReadResult<Entity?>` |
| all query rows | `all(): ReadResult<List<Entity>>` |
| raw count or existence | `rawCount(): ReadResult<Long>` and `rawExists(): ReadResult<Boolean>` |
| ungrouped raw aggregate | `rawMin(...)`, `rawMax(...)`, `rawSum(...)`, and `rawAvg(...)` returning `ReadResult<V?>` |
| grouped raw aggregate | `raw*By(...): ReadResult<List<AggregateBucket<K, V>>>`; the bucket value remains nullable where the SQL aggregate can be null |
| query explanation | remains `explain*(): QueryPlan`, outside the operation-result algebra |
| create or update without a returned entity | `builder.save(): MutationResult<Unit>` |
| create or update with a returned entity | `builder.saveAndLoad(): MutationResult<Entity>` |
| delete an entity handle | `repo.delete(entity): MutationResult<Unit>`; success means the row is absent afterward, whether this call deleted it or it was already absent |
| idempotent delete by id | `repo.deleteById(id): MutationResult<Boolean>`; `Success(true)` means this call deleted the row and `Success(false)` means it did not |
| bulk delete | `repo.deleteMany(...): MutationResult<Int>` |
| bulk create | `repo.createMany(...): MutationResult<List<Entity>>` |

The existing privacy-scanning `visible*` query terminals are removed as
described below. A completed unique-index helper retains `query()` for callers
that need further query composition; its four direct `orNull`,
`visibleOrNull`, `orError`, and `orThrow` terminals collapse to the single
`find()` terminal plus result transformations and projections.

Query explanation is diagnostic introspection rather than application-data
execution. Its existing contract remains distinct: interceptor rejection
produces a diagnostic `QueryPlan` with `rejected = true`, and
`requireNotRejected()` is its explicit throwing projection. Other exceptions
while constructing or obtaining a plan throw normally. Wrapping explanation in
`ReadResult` would incorrectly turn the rejection the caller asked to inspect
into a failed explanation and discard the plan-shaped diagnostic contract.

Bulk operations return one result for the operation as a whole. Canonical
`createMany()` and `deleteMany()` are atomic: EntKt uses one transaction for
candidate selection and processing, every internal batch or row write, and
write-side lifecycle callbacks. Any failure before successful completion of
that work aborts the whole operation. A confirmed rollback is
a mutation failure carrying `NotPersisted`; no successful subset is exposed or
left committed.

Hydration that write-side work itself requires is part of that write-side
processing, not of return processing: per the `save()` contract,
implementations may hydrate database-generated fields internally when
lifecycle callbacks or persistence bookkeeping need them, and a hydration
failure there is an ordinary pre-completion failure that aborts the batch.
Return processing defers only what the *returned* values additionally
require — LOAD disclosure, plus any return materialization not already
performed for write-side work.

For `createMany()`, once the entire batch's writes and write-side lifecycle work
have succeeded, failure to materialize or disclose the requested returned
entities follows the ordinary `saveAndLoad()` rule. An EntKt-owned transaction
commits the complete batch and returns a failure carrying `Committed`; a
caller-owned transaction returns a failure whose state is
`TransactionPending`, marks the scope rollback-only, and is later reported as
`NotCommitted` or `OutcomeUnknown` by the transaction boundary. A mutation
whose persistence cannot be established uses `PersistenceUnknown`.

Return processing follows the public result's input order and is fail-fast. The
first materialization exception or LOAD denial ends return processing; EntKt
does not inspect later returned entities solely to aggregate diagnostics and
never exposes a partial list. A privacy failure identifies that one entity in
`EntMutationPrivacyDeniedException` with `operation = EntOperation.LOAD`. This
affects only return processing: every write and write-side lifecycle callback
has already succeeded, so the complete batch still follows the commit-state
rules above.

This does not add per-input success values, failure indexes, compensation,
skipping of denied or invalid deletion candidates, or implicit create-conflict
handling. Those require a separately named future operation with an explicit
partial-success contract rather than changing either canonical bulk method's
meaning. The separate Preflighted Bulk Operations RFC may later evaluate all
privacy and validation decisions before the first write and aggregate their
diagnostics; that enhancement is not required for database atomicity.

### Result Construction Authority

Result values are publicly inspectable and exhaustively matchable, but only
EntKt may create the framework-owned `Failed` variants without opting into an
internal API. Their constructors are `internal`, and
`@ConsistentCopyVisibility` gives each generated data-class `copy()` the same
visibility. Ordinary application code therefore cannot fabricate a failure or
copy a real failure with a different exception or reported state.

Generated code is compiled in the application module, so it cannot call an
`internal` runtime constructor directly. Each result companion instead exposes
a public `failedForInternalUse(...)` factory guarded by the existing
`@EntktInternal` error-level opt-in. Generated files already opt in to that
marker. An application can deliberately opt in and call the escape hatch, so
this is an API guardrail rather than a security boundary; accidental use is a
compile error.

The same escape hatch is the initial test-fixture path for application tests
that need to exercise exhaustive `Failed` branches without executing an
operation. Such tests may use `@OptIn(EntktInternal::class)` and accept
responsibility for constructing a state that EntKt could actually produce.
This RFC does not add a separate `EntktTestApi` marker, public fixture factory,
or test-fixtures artifact; a dedicated testing surface may be added later if
broader needs justify it.

`Success` constructors remain public. Constructing a success is useful for
ordinary result transformations and tests and cannot change EntKt's internal
transaction state. In particular, it cannot clear a transaction scope that
EntKt has already marked rollback-only.

`DriverTransactionResult` is the exception to the restricted-failure rule.
Its variants remain publicly constructible because third-party driver
implementations are responsible for reporting authoritative transaction
outcomes to EntKt.

## Read Result

### Public Failure Payloads

`ReadResult` does not accept the universal `EntError` hierarchy. Every failed
read carries an exception directly rather than duplicating the same condition
as both a result variant and an exception used by throwing projections.

```kotlin
data class EntityKey(
    val field: String,
    val value: Any,
)

data class PrivacyDenial(
    val entityType: String,
    val entityKey: EntityKey,
    val reason: String,
)

data class EagerEdgeStep(
    val sourceEntityType: String,
    val edgeName: String,
    val targetEntityType: String,
)

sealed interface LoadDenialOrigin {
    data object Root : LoadDenialOrigin

    data class EagerEdge(
        val path: List<EagerEdgeStep>,
    ) : LoadDenialOrigin {
        init {
            require(path.isNotEmpty())
        }
    }
}
```

`PrivacyDenial` is retained as the structured payload of
`EntPrivacyDeniedException`. `EntityKey` is a type-erased runtime wrapper around
the entity's generated ID field; it permits trusted callers to correlate each
denial without exposing the hydrated entity or its non-key fields. LOAD is
implicit from the exception type, so the payload does not repeat an operation
enum. The key and privacy-rule-supplied `reason` are trusted diagnostic data;
applications must not expose them to untrusted clients without an explicit
boundary mapping.

`Failed(exception)` preserves the exception EntKt observed. Driver,
materialization, interceptor, and privacy-rule exceptions are not repackaged as
driver-specific or materialization-specific payloads. When EntKt itself must
represent a non-exceptional procedural failure, it constructs a narrowly typed
exception such as `EntQueryRejectedException` and places that exception
directly in `Failed`.

Generated read execution classifies known failures where they occur:

1. An explicit interceptor rejection becomes
   `Failed(EntQueryRejectedException(...))`.
2. An ordinary exception thrown by the driver call becomes `Failed(exception)`.
3. An ordinary row-to-entity conversion exception becomes `Failed(exception)`.
4. A root LOAD denial becomes
   `Failed(EntPrivacyDeniedException(Root, ...))`; strict eager-edge denial uses
   the same exception with `EagerEdge(path)`.
5. Other ordinary exceptions thrown while EntKt invokes an interceptor or
   privacy rule become `Failed(exception)`.

The terminal also has a final `Exception` boundary so an unexpected ordinary
exception, including an EntKt invariant failure reached during execution,
cannot escape the result algebra. It rethrows `CancellationException` before
constructing `Failed` and never catches `Throwable`, so JVM `Error`s propagate.
Failures detected before a result-bearing terminal begins, such as invalid
query-builder arguments or generation-time metadata errors, continue to throw
normally because no result boundary exists yet.

### Replacing `EntError`

This RFC retires `EntError` as a canonical public payload. Its universal
hierarchy permits impossible combinations: a read can carry mutation-only
errors, while a mutation can carry read-only scan or query-rejection errors.
Renaming that hierarchy to `EntFailure` would preserve the same defect.

The replacement is operation-specific: read failures carry an `Exception`,
while mutation failures carry an `EntMutationException` whose
`MutationWriteState` records the database effect. Typed exceptions retain
structured privacy, validation, constraint, conflict, and target-absence
details without duplicating each one as a result variant.

`EntException` remains the common base for framework-created exceptions, but it
no longer exposes `val error: EntError`. Each typed exception retains its
specific payload:

```kotlin
abstract class EntException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

enum class MutationWriteState {
    NotPersisted,
    TransactionPending,
    Committed,
    PersistenceUnknown,
}

sealed class EntMutationException(
    val writeState: MutationWriteState,
    message: String,
    cause: Throwable? = null,
) : EntException(message, cause)

class EntTargetAbsentException(
    val entityType: String,
    val key: EntityKey,
) : EntMutationException(
    MutationWriteState.NotPersisted,
    "$entityType with ${key.field}=${key.value} not found",
)

class EntMutationPrivacyDeniedException(
    writeState: MutationWriteState,
    val entityType: String,
    val operation: EntOperation,
    val entityKey: EntityKey?,
    val reason: String,
) : EntMutationException(
    writeState,
    "$operation denied on $entityType: $reason",
)

data class ValidationViolation(
    val message: String,
    val field: String? = null,
    val code: String? = null,
)

class EntValidationException(
    val entityType: String,
    val operation: EntOperation,
    val violations: List<ValidationViolation>,
) : EntMutationException(
    MutationWriteState.NotPersisted,
    "Validation failed on $entityType",
) {
    init {
        require(violations.isNotEmpty())
    }
}

class EntConstraintViolationException(
    val entityType: String,
    val operation: EntOperation,
    val constraint: String?,
    val field: String?,
    val driverCode: String?,
    message: String,
    cause: Exception,
) : EntMutationException(MutationWriteState.NotPersisted, message, cause)

class EntConflictException(
    val entityType: String,
    val operation: EntOperation,
    val code: String?,
    message: String,
    cause: Exception? = null,
) : EntMutationException(MutationWriteState.NotPersisted, message, cause)

class EntUnexpectedMutationException(
    writeState: MutationWriteState,
    cause: Exception,
) : EntMutationException(
    writeState,
    "Unexpected mutation failure with write state $writeState",
    cause,
)

class EntQueryRejectedException(
    val entityType: String,
    val reason: String,
    val code: String?,
    val interceptor: String,
) : EntException(reason)

class EntPrivacyDeniedException(
    val origin: LoadDenialOrigin,
    val denials: List<PrivacyDenial>,
) : EntException("LOAD denied for ${denials.size} ${denials.singleOrNull()?.entityType ?: "entities"}") {
    init {
        require(denials.isNotEmpty())
    }
}

```

`EntOperation` and `ValidationViolation` remain small shared metadata types;
they are not universal failure algebras. A create privacy denial may not yet
have a usable entity key, so `EntMutationPrivacyDeniedException.entityKey` is
nullable. Target absence always has the requested key. A recognized driver
constraint preserves the original driver exception as its cause, while an
expected conflict detected from an affected-row count may have no underlying
exception. Target absence, validation, recognized constraint violations, and
expected conflicts hardcode `NotPersisted`; only mutation privacy and
unexpected mutation exceptions accept a state because those failures can be
reached at more than one mutation phase.

`EntMutationException` is sealed because every direct failure kind is owned by
the runtime module. Application callbacks and third-party drivers preserve
custom exceptions as causes rather than adding hierarchy members. This permits
exhaustive `when` handling; adding a new direct subtype is consequently a
source-breaking algebra change that must be recorded as such.

These direct properties are the supported structured inspection surface.
Callers do not parse exception messages, and the replacement exceptions do not
wrap a second `EntError`-like payload. Unclassified driver, application, and
materialization exceptions are preserved as the cause of
`EntUnexpectedMutationException`; EntKt does not introduce separate driver,
hook, or materialization exception types.

Framework-created exceptions retain ordinary JVM stack traces. This is an
intentional initial cost: read and mutation `getOrThrow()` projections throw
the stored exception directly, while transaction `getOrThrow()` preserves it
as the wrapper's cause. Suppressing stack capture would make privacy,
validation, and other typed failures harder to diagnose. EntKt may reconsider
stackless expected-path exceptions only after profiling demonstrates a material
cost and a design preserves useful throwing diagnostics.

`origin` identifies whether the terminal's root selection or an eager-loaded
target produced the denial. An eager origin carries the complete generated
schema-edge path from the root to the denied target. Each step contains only
schema type and edge names; it never contains hydrated entities, field values,
or viewer data. Root privacy completes before eager loading begins, so one
exception never mixes both origins.

`ReadResult.getOrThrow()` throws the stored exception directly. Framework-owned
procedural failures use narrower exception types such as
`EntQueryRejectedException` so callers can target them without recovering a
universal `EntError` payload.

Existing variants migrate as follows:

| Current `EntError` | Canonical result state |
|---|---|
| `NotFound` from a read | `ReadResult.Success(null)` |
| `PrivacyDenied` from LOAD | `ReadResult.Failed(EntPrivacyDeniedException(...))` |
| `QueryRejected` | `ReadResult.Failed(EntQueryRejectedException(...))` |
| `DriverFailure` from a read | `ReadResult.Failed(originalException)` |
| `OverfetchCapExceeded` | removed with legacy privacy-scanning terminals; no canonical equivalent |
| read-side row conversion currently classified as `DriverFailure` | `ReadResult.Failed(originalException)` |
| `NoChanges` | `MutationResult.Success` after the update pipeline verifies that the target exists and produces no assignments |
| `ValidationFailed` | `MutationResult.Failed(EntValidationException(...))`; the exception hardcodes `NotPersisted` |
| `ConstraintViolation` | `MutationResult.Failed(EntConstraintViolationException(...))`; the exception hardcodes `NotPersisted` |
| `Conflict` | `MutationResult.Failed(EntConflictException(...))`; the exception hardcodes `NotPersisted` |
| `WriteSucceededLoadDenied` | `MutationResult.Failed(EntMutationPrivacyDeniedException(writeState, operation = EntOperation.LOAD, ...))` |

The implemented `EntResult<T>` and generated `*OrError()` methods become
migration inputs, not layers underneath `ReadResult`. Canonical read execution
constructs `ReadResult` directly, and throwing projections throw its stored
exception without another wrapper.

### Variants

`Success(value)` means the read completed reliably. The payload type defines
the operation's successful cardinality:

- `Success(entity)` means a singular lookup found its entity.
- `Success(null)` means a singular lookup authoritatively found no entity.
- `Success(values)` means a collection read completed; `values` may be empty
  but cannot be `null`.

`Failed(exception)` means the read did not return either a value or an
authoritative absence. The exception may be an ordinary exception observed by
EntKt or a framework-created typed exception. LOAD denial uses
`EntPrivacyDeniedException`, whose non-empty `denials` payload identifies the
selected rows that existed but could not be returned under the current viewer.

### Collection Semantics

Collection reads return a non-null list, including when no rows match:

```kotlin
query.all() == ReadResult.Success(emptyList())
```

The declared type is `ReadResult<List<T>>`, not `ReadResult<List<T>?>`, so
`Success(null)` is not representable for a collection operation. Null is an
authoritative-absence value only for singular lookup signatures.

A strict collection read is all-or-nothing. If any row in the selected storage
window is denied, `all()` returns
`Failed(EntPrivacyDeniedException(Root, ...))`; it does not return a partial
list.
EntKt evaluates LOAD privacy for the entire selected window and includes one
`PrivacyDenial` for every denied root row, in encountered query order. The
exception's list is guaranteed non-empty and contains row keys but no hydrated
entities or non-key field values. If evaluating any row throws an ordinary
exception, the read returns that exception in `Failed` instead of presenting an
incomplete denial aggregate as complete.

### Exact-Window Semantics

The default query posture is SQL-shaped:

```kotlin
query.firstOrNull()
```

The driver executes the exact query window requested by the caller. If the
first selected row fails LOAD privacy, the result is
`Failed(EntPrivacyDeniedException(Root, listOf(denial)))`. EntKt does not
silently scan the second row.

Similarly, strict `all()` evaluates the rows in the selected storage window;
`limit` and `offset` retain their ordinary storage-query meaning.

This property matters for predictability and performance: choosing a result
representation never turns a one-row query into a scan.

## Read Projections

The runtime module supplies generic extensions rather than generating them on
every repository and query type.

### `getOrThrow()`

```kotlin
fun <T> ReadResult<T>.getOrThrow(): T
```

```text
Success -> return value, preserving its declared nullability
Failed  -> throw the stored exception directly
```

Framework-created exceptions retain their structured diagnostics as payloads.
For `ReadResult<T?>`, this returns `T?`; authoritative absence remains `null`
because it is a successful lookup result.

The operation name, declared success type, and projection have separate
responsibilities:

```kotlin
repo.findById(id).getOrThrow()
query.firstOrNull().getOrThrow()
```

`findById()` follows Kotlin's nullable `find()` convention, while
`firstOrNull()` distinguishes the nullable operation from Kotlin's strict
`first()`. Their `ReadResult<T?>` signatures state that authoritative absence
is a successful null payload. `getOrThrow()` states that privacy denial or
operational failure throws. This matches Kotlin `Result<T>.getOrThrow()`, which
returns a successful value exactly as declared, including when `T` is nullable.

### No general nullable failure projection

`ReadResult` does not provide `get()` or a general `getOrNull()`:

- `get()` would have an unclear relationship to `getOrThrow()`.
- `getOrNull()` would encourage callers to turn privacy and operational
  failures into the same `null` used for authoritative absence. In a database
  API, that can make an outage or malformed result look like a missing row.

Operational failures must remain distinct unless a caller explicitly handles
them. LOAD denial has one narrow transformation because mapping it to absence
is a common privacy-boundary operation.

### `visibleOrNull()`

```kotlin
fun <T : Any> ReadResult<T?>.visibleOrNull(): ReadResult<T?>
```

`visibleOrNull()` transforms an already-produced singular read result:

```text
Success(value)                                    -> Success(value)
Success(null)                                     -> Success(null)
Failed(EntPrivacyDeniedException(Root, ...))      -> Success(null)
Failed(EntPrivacyDeniedException(EagerEdge(path), ...)) -> unchanged
Failed(otherException)                            -> unchanged
```

It performs no I/O, does not re-run LOAD privacy, and never scans another row.
The transformation answers only whether the selected root is visible. It does
not turn a visible root into `null` because some requested nested entity was
denied; adding an eager load therefore cannot change root presence into
apparent absence.
This permits concise privacy-as-absence handling while preserving operational
failure behavior. The transformation intentionally discards the privacy-denial
details; callers that need them must inspect the original `Failed` result:

```kotlin
val user = users.findById(id)
    .visibleOrNull()
    .getOrThrow()
```

The function is defined for nullable singular results. It maps denial of the
root entity to absence; it does not turn a visible root into `null` because an
eagerly loaded target was denied. Canonical collection operations continue to
return `ReadResult<List<T>>`; they do not acquire a nullable success state or
silently discard denied root rows.

## Eager-Edge Privacy

The default API is strict for eagerly loaded edges. If LOAD privacy denies any
eagerly loaded target, the root terminal returns
`ReadResult.Failed(EntPrivacyDeniedException(EagerEdge(path), ...))`. It does not
return a partially visible graph, silently omit the target, or convert the
containing edge to an unloaded state. A root denial instead uses
`EntPrivacyDeniedException(Root, ...)`, allowing `visibleOrNull()` to map only
root denial to singular absence.

Edge load state continues to describe only whether an edge was requested and
loaded. It does not gain a privacy-specific `Denied` state.

Callers may explicitly retain only visible targets for one eager edge. The
modifier follows the `with<Edge> {}` block rather than appearing among target
query predicates, ordering, and bounds:

```kotlin
withProfile {
    // target query configuration
}.filterVisible()

withPosts {
    orderBy(Post.createdAt.desc())
    limit(10)
}.filterVisible()
```

Each generated `with<Edge> {}` returns an edge-specific configuration handle:

```kotlin
interface EagerLoad<out ParentQuery> {
    fun filterVisible(): ParentQuery
}

fun UserQuery.withPosts(
    block: PostQuery.() -> Unit = {},
): EagerLoad<UserQuery>
```

Ignoring the returned handle keeps the strict default. Calling
`filterVisible()` configures that exact edge and returns the parent query so a
fluent chain may continue. The handle prevents the modifier from accidentally
changing root-query privacy or whichever eager edge happened to be configured
most recently.

`filterVisible()` changes only the eager edge represented by its handle:

- a denied to-one target produces `EdgeState.Loaded(null)`
- denied to-many targets are omitted from the non-null loaded list
- EntKt does not scan beyond the selected eager-load window to replace an
  omitted target
- the setting is not inherited by nested eager loads; each nested edge must
  opt in independently
- root LOAD denial remains a terminal failure and is unaffected by the modifier
- eager-query rejection and ordinary privacy, driver, or materialization
  exceptions remain terminal failures; only a returned LOAD-deny decision is
  omitted

The strict default remains unchanged when `filterVisible()` is absent. The
related root transformation `visibleOrNull()` maps singular root denial to
absence. Root collection reads remain strict; this RFC does not introduce a
root-query filtering modifier or restore privacy-scanning collection terminals.

Strict eager privacy is fail-fast. The first denied target produces an
`EagerEdge` exception containing exactly one `PrivacyDenial`; EntKt does not
execute later eager work solely to collect more diagnostics. Evaluation follows
generated schema edge declaration order, recursively completes a configured
edge's nested eager loads before the next sibling edge, and evaluates targets
in their target-query result order. Root collection privacy remains different:
it evaluates and reports every denied root in the already-selected root window.

## Mutation Result

Mutation terminals return one binary result. The success payload distinguishes
an acknowledgement-only write from a write whose entity was requested, while
every unsuccessful terminal returns a mutation exception containing EntKt's
known write state:

```kotlin
sealed interface MutationResult<out T> {
    data class Success<T>(
        val value: T,
    ) : MutationResult<T>

    @ConsistentCopyVisibility
    data class Failed internal constructor(
        val exception: EntMutationException,
    ) : MutationResult<Nothing>

    companion object {
        @EntktInternal
        fun failedForInternalUse(
            exception: EntMutationException,
        ): MutationResult<Nothing> = Failed(exception)
    }
}
```

`Failed` means the terminal did not produce its requested success value. It
does not imply that the database write rolled back. The exception explains why
the requested mutation terminal failed, and `exception.writeState` records what
EntKt knows about its database effect so callers can make safe retry decisions
from the raw result.

### Variant Semantics

`Success(value)` means the requested mutation terminal completed. As with
ordinary transactional APIs, success inside a caller-owned transaction means
the mutation is staged in that transaction; it does not claim that the outer
transaction has committed.

For `save()`, the value is `Unit`. For `saveAndLoad()`, the value is the
materialized entity:

```kotlin
createBuilder.save(): MutationResult<Unit>
createBuilder.saveAndLoad(): MutationResult<Entity>
updateBuilder.save(): MutationResult<Unit>
updateBuilder.saveAndLoad(): MutationResult<Entity>
repo.delete(entity): MutationResult<Unit>
repo.deleteById(id): MutationResult<Boolean>
repo.deleteMany(...): MutationResult<Int>
repo.createMany(...): MutationResult<List<Entity>>
```

Single-row deletes are idempotent but expose different acknowledgements.
`delete(entity)` treats the supplied entity as an ID handle, reloads current
database state, and returns `Success(Unit)` whether it deletes the row or finds
it already absent. `deleteById(id)` runs the same pipeline but preserves the
affected-row signal: `Success(true)` only when this call deletes the row and
`Success(false)` when the row was absent at reload time or disappeared before
the final delete. Neither case produces `EntTargetAbsentException`.

DELETE privacy, validation, and lifecycle callbacks run against the freshly
reloaded row, not caller-supplied entity fields. If no row exists at reload
time, none of those callbacks run. If the row disappears after reload,
before-delete callbacks may already have run; after-delete callbacks run only
when the final delete actually removes the row.

`save()` still performs normalization, validation, mutation privacy,
persistence, and lifecycle work required by the mutation pipeline. It does not
apply returned-entity LOAD privacy because it does not disclose an entity.
Implementations may still hydrate database-generated fields internally when
hooks or persistence bookkeeping require them; that is not part of the public
return contract.

`saveAndLoad()` performs the same mutation and additionally materializes the
returned entity and applies post-write LOAD privacy. A successful returned
entity is non-null. Target absence fails with a typed exception rather than a
nullable success payload.

The `Load` suffix is intentional: the returned value is an entity subject to
the ordinary LOAD contract, not merely a raw value produced by persistence.

Known mutation conditions become typed exceptions inside `Failed`:

| Condition | Exception | Write state |
|---|---|---|
| update target is absent | `EntTargetAbsentException` | `NotPersisted` |
| mutation privacy denies the operation | `EntMutationPrivacyDeniedException` | `NotPersisted` |
| validation rejects the mutation | `EntValidationException` containing the violations | `NotPersisted` |
| a recognized database constraint is violated and rolled back | `EntConstraintViolationException` containing the violation | `NotPersisted` |
| an expected concurrency or compare-and-set conflict occurs | `EntConflictException` containing the conflict | `NotPersisted` |
| returned-entity LOAD privacy denies `saveAndLoad()` | `EntMutationPrivacyDeniedException` | current mutation write state |
| materialization, hook, or driver execution throws | `EntUnexpectedMutationException` preserving the original as its cause | current mutation write state |

The first five rows are expected mutation failures, but they do not need
parallel result variants and exception classes describing the same condition.
Their typed exceptions retain the structured payloads callers may need.

A read LOAD denial uses `EntPrivacyDeniedException`. Any privacy denial surfaced
by a mutation terminal, whether it rejects the mutation or prevents
`saveAndLoad()` from disclosing the returned entity, uses
`EntMutationPrivacyDeniedException`. Its payload identifies the entity,
privacy operation, optional entity key, and safe diagnostic reason. `operation`
identifies the privacy decision that was denied, not necessarily the mutation
terminal that initiated it: pre-write rejection uses the mutation operation
(`CREATE`, `UPDATE`, `DELETE`, or `EDGE_MUTATION`), while returned-entity
disclosure denial uses `LOAD`. `writeState` independently reports the database
effect. A rejected no-op update and a returned-entity denial after an allowed
no-op update can therefore both be `NotPersisted`, but their operations are
`UPDATE` and `LOAD`, respectively.

`Failed(exception)` does not by itself mean that the database write failed.
`exception.writeState` distinguishes a no-op or pre-write rejection
(`NotPersisted`), a write staged in a caller-owned transaction
(`TransactionPending`), a committed write (`Committed`), and an uncertain
outcome (`PersistenceUnknown`). The exact transaction-boundary rules are
defined below.

EntKt constructs `Failed`; application callbacks never return it. Hooks,
normalizers, derivations, validators, privacy rules, and other mutation
extension points retain their natural signatures. A validator returns its
ordinary valid/invalid decision and a privacy rule returns its ordinary
allow/deny decision. An expected invalid or denied decision becomes a typed
exception in `Failed`; an ordinary exception thrown across an application
callback boundary becomes `EntUnexpectedMutationException` with EntKt's
current state and the thrown exception as its cause.

Known failures are classified at their specific execution boundaries so EntKt
can retain the most accurate write state. Classification is positional rather
than based on the thrown object's runtime type: an exception crossing an
application callback boundary is foreign even if application code constructed
an EntKt exception type. It is therefore wrapped in
`EntUnexpectedMutationException` rather than accepted as a framework-classified
validation, privacy, or constraint failure. The terminal also has a final
`Exception` boundary: any unexpected ordinary exception that reaches it,
including an EntKt invariant failure during execution, becomes an
`EntUnexpectedMutationException` with the current state. It rethrows
`CancellationException` before constructing `Failed` and never catches
`Throwable`, so JVM `Error`s propagate unchanged.

Mutation results do not return a receipt or idempotency token. EntKt reports
write-state certainty; applications own idempotency, compensation, and
reconciliation policies through mechanisms such as client-generated IDs,
unique business keys, request-key tables, or durable outboxes. A token returned
after execution could not make an uncertain retry safe because the response
containing it may itself be lost.

### Mutation Write State

`MutationWriteState` records what EntKt knows about this mutation's database
effect:

- `NotPersisted` means the mutation has no durable effect. It did not execute,
  was rejected before execution, or was rolled back successfully.
- `TransactionPending` means its SQL executed inside a caller-owned transaction
  that remains open. The transaction owner still determines whether it commits.
- `Committed` means EntKt received confirmation that the transaction committed.
- `PersistenceUnknown` means EntKt cannot determine whether it committed,
  normally because the driver connection failed during commit or outcome
  confirmation.

`Failed.exception` always carries a `MutationWriteState`, including expected
pre-write failures. Target absence, validation rejection, and recognized
constraints or conflicts hardcode `NotPersisted`. Mutation privacy and
unexpected failures use the state EntKt assigns at their execution boundary.

A successful terminal does not return a write state: success in a caller-owned
transaction has the ordinary transactional meaning of successful staging,
while success from an EntKt-owned transaction means its commit completed.

Returning a failure with `MutationWriteState.TransactionPending` from a mutation
executed through a transaction-scoped client marks that transaction scope
rollback-only. The caller normally also projects it through `orRollback()` to
stop the block immediately. `orRollback()` controls whether dependent code
continues; it is not required to make a failed transaction-client mutation roll
back. A mutation result produced through another client has no control over the
current transaction unless the caller explicitly applies the current scope's
`orRollback()` projection to it.

EntKt must not automatically retry `PersistenceUnknown`. It preserves the
original exception as the cause, treats the affected connection and transaction
as unusable, and leaves reconciliation or idempotent retry to the application.

### Driver Write-Certainty Contract

The current `Driver.withTransaction()` value-or-throw contract does not expose
whether a failure was followed by a confirmed rollback or left the transaction
outcome unknown. The canonical algebra therefore replaces that part of the
public driver SPI with a structured outcome:

```kotlin
enum class TransactionFailureState {
    NotCommitted,
    OutcomeUnknown,
}

sealed interface DriverTransactionResult<out T> {
    data class Success<T>(
        val value: T,
    ) : DriverTransactionResult<T>

    data class Failed(
        val exception: Exception,
        val transactionState: TransactionFailureState,
    ) : DriverTransactionResult<Nothing>
}

fun <T> Driver.withTransaction(
    block: (Driver) -> T,
): DriverTransactionResult<T>
```

A transaction returns `Success(value)` only after commit is confirmed. If the
block fails and rollback is confirmed, the driver returns
`Failed(..., TransactionFailureState.NotCommitted)`. A rollback failure returns
`OutcomeUnknown`. A commit failure also returns `OutcomeUnknown` even if a later
rollback call appears to succeed, because the failed commit may already have
reached the database. Cleanup failures after a confirmed commit must not turn a
successful commit into an unknown or failed outcome.

`TransactionFailureState` is intentionally narrower than mutation
`MutationWriteState`. A completed transaction cannot be `TransactionPending`,
and this RFC has no operation that can fail after a confirmed commit, so
`Committed` is not a transaction failure state. A future post-commit API may
add that state when it defines a real path that requires it.

The generated transaction-scoped client is an `EntTransactionClient`, which
has no `withTransaction()` member, so client-level nesting does not compile.
Calling `withTransaction()` on a transaction-scoped driver remains unsupported
and throws `NestedTransactionUnsupportedException` before entering the block or
performing transaction I/O.

The driver rolls back and rethrows `CancellationException` and JVM `Error`s;
it does not store them in `DriverTransactionResult.Failed`. The generated
client converts an ordinary failed driver transaction to `TransactionResult`.

Driver exception classification becomes mutation-specific and returns the
state-bearing framework exception directly:

```kotlin
fun Driver.classifyMutationException(
    exception: Exception,
    entity: String,
    operation: EntOperation,
): EntMutationException?
```

Read execution does not call this method; `ReadResult.Failed` preserves the
original driver exception. For a mutation, a recognized constraint returns
`EntConstraintViolationException`, whose type hardcodes `NotPersisted`. An
unclassified operational failure for which the driver knows the persistence
outcome returns `EntUnexpectedMutationException` with that state and the
original exception as its cause. Returning `null` means the driver has no more
precise classification.

Generated mutation execution independently tracks its phase: before
persistence it uses `NotPersisted`; after successful SQL on a
transaction-scoped driver it uses `TransactionPending`; and after successful
autocommit SQL it uses `Committed`. If a driver-call exception reaches the
terminal without a more precise classification, generated code wraps it in
`EntUnexpectedMutationException(PersistenceUnknown, cause)`, never optimistically
`NotPersisted`.

Driver implementations are trusted to assess persistence certainty at their
own boundary. State appears only on the returned `EntMutationException`; there
is no parallel nullable state field that can contradict it. Application
callback exceptions never pass through driver classification and continue to
follow the positional provenance rule above.

These SPI types are deliberately about execution certainty, not application
error policy. Driver implementations may construct a sealed framework mutation
exception but do not construct `ReadResult`, `MutationResult`, or
`TransactionResult`.

### No-Op Updates

No separate `Unchanged` outcome is part of the algebra. An existing-target
update that contains no assignments after normalization, pre-write hooks,
privacy, validation, and derivation completes as `Success`. `save()`
returns `Unit`; `saveAndLoad()` returns the current entity after applying its
ordinary LOAD privacy contract. A missing target produces
`Failed(EntTargetAbsentException(...))`, whose exception hardcodes
`NotPersisted`, so an empty update must still establish whether its target
exists.

If that LOAD check denies the returned entity, `saveAndLoad()` returns
`Failed(EntMutationPrivacyDeniedException(MutationWriteState.NotPersisted,
...))`. `NotPersisted` is accurate because the successful no-op save performed
no database write.

The persist phase and existing post-persist lifecycle callbacks do not run when
the pipeline produced no assignments. Pre-write phases still run because they
may reject the operation or derive an assignment that turns it into a real
write.

EntKt does not compare assigned values with the current row to optimize away an
apparently equal update. Once a caller or pipeline stage assigns a persisted
field, EntKt executes the update so database triggers, version increments,
audit behavior, and other write semantics remain observable.

## Persistence And Commit-Boundary Invariants

The mutation algebra is only trustworthy if every failure path obeys these
rules:

1. Pre-persist privacy, validation, and hook rejection cannot report a
   committed state.
2. Constraint failures roll back the framework-owned write transaction before
   returning `Failed(EntConstraintViolationException(...))`; the exception
   hardcodes `NotPersisted`.
3. `Success` inside a caller-owned transaction reports successful staging, not
   commitment of that transaction. Commit failure is surfaced by the owning
   transaction boundary.
4. A driver failure whose commit result is genuinely unknown must use
   `Failed(EntUnexpectedMutationException(MutationWriteState.PersistenceUnknown,
   ...))`; it cannot be reported as definitely uncommitted.
5. An existing post-persist lifecycle callback that throws after an autocommit
   write uses
   `EntUnexpectedMutationException(MutationWriteState.Committed, cause)`. The
   same callback inside a caller-owned transaction uses `TransactionPending`
   and marks that transaction rollback-only. Failure to materialize the value
   requested by `saveAndLoad()` likewise uses
   `EntUnexpectedMutationException`; returned-entity privacy denial uses
   `EntMutationPrivacyDeniedException`. In both cases the exception preserves
   the reason and carries the database effect.

For `saveAndLoad()`, successful persistence is not undone merely because the
requested return value cannot be materialized or disclosed. When EntKt owns the
transaction and the write has executed successfully, it commits the usable
transaction and returns a failure whose exception carries `Committed`. When
the caller owns the transaction, EntKt returns a failure whose exception
carries `TransactionPending` and marks the transaction scope rollback-only.
`TransactionPending` describes the mutation when its result is produced; after
the block exits, the enclosing `TransactionResult` reports `NotCommitted` if
rollback is confirmed or `OutcomeUnknown` if rollback cannot be confirmed.
`orRollback()` remains the normal way to stop dependent code immediately. An
assignment-free update returns a failure whose exception carries
`NotPersisted` because no write occurred. If a connection or commit failure
prevents EntKt from determining the outcome, the mutation exception carries
`PersistenceUnknown` rather than an assumed committed or uncommitted state.

## Mutation Projections

Canonical writes return their exhaustive result:

```kotlin
client.users.create { ... }.save(): MutationResult<Unit>
client.users.create { ... }.saveAndLoad(): MutationResult<User>
client.users.update(id) { ... }.save(): MutationResult<Unit>
client.users.update(id) { ... }.saveAndLoad(): MutationResult<User>
```

The runtime module provides one projection for every mutation result:

```kotlin
fun <T> MutationResult<T>.getOrThrow(): T
```

Its value projection is deliberately simple:

```text
Success(value) -> return value
Failed         -> throw
```

Typical use is concise:

```kotlin
client.users.create { ... }.save().getOrThrow()
val user = client.users.create { ... }.saveAndLoad().getOrThrow()
```

`getOrThrow()` returns `Success.value` or throws the stored
`EntMutationException` directly. Each mutation exception carries its
`writeState`, so projection neither introduces a wrapper nor discards whether
the mutation committed or has an uncertain persistence outcome. The raw result
and throwing projection therefore expose the same exception instance.

`getOrThrow()` is a propagation convenience, not a retry policy. A thrown mutation
exception does not imply that the write rolled back, so blanket retry is unsafe:

```kotlin
retry {
    client.users.create { ... }.saveAndLoad().getOrThrow() // unsafe
}
```

Callers deciding whether to retry must inspect both the exception type and
`writeState`. `Committed` must not be repeated as though it failed to persist;
`PersistenceUnknown` requires reconciliation or an idempotent retry mechanism;
`TransactionPending` is resolved by the enclosing transaction; and
`NotPersisted` establishes only that no write survived, not that privacy,
validation, or another deterministic rejection will succeed on retry. The
runtime KDoc for mutation `getOrThrow()` must carry this warning prominently.

There is no mutation `orNull()`. Collapsing every failure to null would hide
materially different states, including a definitely unpersisted write, a
committed write whose callback failed, and an unknown commit outcome. Clients
must inspect the raw result or choose the explicit throwing projection.

This direction supersedes [Explicit Save Terminals](../../possible-features/mutation/explicit-save-terminals.md)
if adopted. That RFC improves the existing result-variant design by preferring
`saveOrThrow()` and `saveOrError()`; this RFC instead makes plain `save()`
exhaustive and moves those behaviors to projections.

No additional privacy-specific mutation projection is part of the initial
API. If repeated handling of LOAD-denied `Failed` results emerges in
application code, a narrowly named helper can be considered later without
changing the `getOrThrow()` contract.

## Transactional Composition

EntKt should retain an explicitly transactional scope for multi-operation
composition. Transactions use a deliberately small algebra because a block may
combine reads, every mutation kind, and arbitrary application code:

```kotlin
sealed interface TransactionResult<out T> {
    data class Success<T>(
        val value: T,
    ) : TransactionResult<T>

    @ConsistentCopyVisibility
    data class Failed internal constructor(
        val exception: Exception,
        val transactionState: TransactionFailureState,
    ) : TransactionResult<Nothing>

    companion object {
        @EntktInternal
        fun failedForInternalUse(
            exception: Exception,
            transactionState: TransactionFailureState,
        ): TransactionResult<Nothing> = Failed(exception, transactionState)
    }
}

class TransactionScope internal constructor() {
    fun <T> ReadResult<T>.orRollback(): T
    fun <T> MutationResult<T>.orRollback(): T
}

class NestedTransactionUnsupportedException : IllegalStateException(
    "Nested withTransaction() is not supported",
)

fun <T> EntClient.withTransaction(
    block: TransactionScope.(EntTransactionClient) -> T,
): TransactionResult<T>
```

The public client has one canonical transaction entry point:

```kotlin
client.withTransaction { tx ->
    val user = tx.users.create { ... }.saveAndLoad().orRollback()
    val note = tx.notes.create {
        ownerId = user.id
    }.saveAndLoad().orRollback()
    note
}: TransactionResult<Note>
```

There is no parallel `transactionResult()` or `withTransactionOrError()` API.
`withTransaction()` returns the exhaustive result; callers wanting throwing
behavior project it explicitly:

```kotlin
val note = client.withTransaction { tx ->
    // ...
}.getOrThrow()
```

There is no transaction `orNull()` projection.

`orRollback()` returns a successful operation value. For a
`ReadResult.Failed` or `MutationResult.Failed`, it uses the stored exception and
stops the block so the current transaction boundary can roll back. The original
result remains available to callers that handle it outside `orRollback()`.

`orRollback()` is a control-flow operation on the currently executing
transaction. It does not claim that the operation producing its receiver ran
in that transaction, and it does not carry or validate result provenance. Only
operations executed through the `tx` client participate in the transaction's
atomic commit or rollback:

```kotlin
client.withTransaction { tx ->
    tx.users.create { ... }.saveAndLoad().orRollback() // transactional
    client.audit.create { ... }.save().orRollback()   // independently executed
}
```

In the second call, a failure aborts the current transaction, but any database
effect independently committed through `client` is not undone. Applications
must use the provided `tx` client for operations that must be atomic. Preventing
mixed-client execution before I/O would require a separate transaction-safety
feature; result-level provenance checking would occur too late to provide that
guarantee.

Every mutation terminal executed through the provided transaction client shares
an internal transaction coordinator. Producing `MutationResult.Failed` marks
the current transaction scope rollback-only and records mutation failures in
encounter order. Final failure precedence is deterministic:

1. If the block exits through `orRollback()` or an ordinary application
   exception, that exit cause is primary. Recorded mutation failures other than
   the identical exception are attached to it as suppressed exceptions in
   encounter order.
2. If the block returns normally while rollback-only, the first recorded
   mutation failure is primary and later recorded failures are attached to it
   as suppressed exceptions.
3. If the block returns normally without becoming rollback-only, EntKt attempts
   commit. A commit exception is primary because no earlier failure required
   rollback.

The first two cases roll back. A confirmed rollback produces
`TransactionResult.Failed` with the primary exception and
`TransactionFailureState.NotCommitted`. An ordinary rollback failure is
attached to the primary exception as suppressed and changes the state to
`OutcomeUnknown`; it does not replace the failure that caused rollback. A
commit failure produces `TransactionResult.Failed` with the commit exception
and `OutcomeUnknown`, and any failure from a subsequent cleanup or rollback
attempt is suppressed on that commit exception. A later rollback attempt cannot
prove that a failed commit did not reach the database.

This fail-closed rule is a correctness backstop, not the normal composition
style. `orRollback()` remains the ordinary way to extract a successful value
and stop the block immediately on failure, avoiding execution of code that
depends on a mutation that did not succeed. Merely ignoring a failed mutation
cannot accidentally commit earlier writes. In other words, `orRollback()`
controls immediate block execution; the coordinator's rollback-only state
controls transaction safety.

Read failures do not mark the scope rollback-only merely by being constructed:
a caller may explicitly transform root LOAD denial with `visibleOrNull()`, and
immutable read-result transformations cannot retroactively change a transaction
coordinator. A required read therefore uses `orRollback()`. A database read
failure that puts the underlying transaction into an aborted state still makes
the transaction boundary fail rather than report a successful commit.

The transaction-scoped `tx` value is an `EntTransactionClient`. It exposes the
generated repositories, `currentPrivacyContext()`, `withPrivacyContext()`, and
`bypassPrivacy_DANGEROUS()`, but no `withTransaction()` member. Privacy
re-scoping returns another `EntTransactionClient`, so it cannot restore the
root-only transaction entry point. The lower-level driver retains a runtime
guard for direct nested driver calls.

The transaction boundary owns commit failures. A failure before commit is
attempted is rolled back when possible; an exception during commit uses the
driver's structured transaction outcome and reports `OutcomeUnknown`. An
earlier successful mutation result from inside the block cannot be changed
after it has been returned; transaction completion is represented by the
enclosing `TransactionResult`.

`TransactionResult.Failed` deliberately does not return the block's value. Its
`transactionState` is structurally limited to `NotCommitted` or
`OutcomeUnknown`.
`orRollback()` is available only inside a transaction scope because outside it
there is no current transaction it could roll back.

```kotlin
fun <T> TransactionResult<T>.getOrThrow(): T
```

`TransactionResult.getOrThrow()` performs no I/O. It returns `Success.value` or
throws an `EntTransactionFailedException` that retains `transactionState` and
exposes the stored exception through its non-null `exception` property while
also using it as the standard exception cause. The wrapper is required because
rethrowing the stored exception alone would discard whether rollback was
confirmed or the transaction outcome is unknown.

The projection asymmetry is intentional: mutation failures already store a
state-bearing framework exception, while transaction failures may store an
arbitrary block exception and therefore require a uniform wrapper to preserve
the final transaction state. When the cause is a mutation exception, its
`TransactionPending` state describes the earlier mutation result; the wrapper's
`NotCommitted` or `OutcomeUnknown` describes the completed transaction.

```kotlin
class EntTransactionFailedException(
    val transactionState: TransactionFailureState,
    val exception: Exception,
) : EntException("Transaction failed with state $transactionState", exception)
```

Transaction `getOrThrow()` is also a propagation convenience, not a retry policy:

```kotlin
retry {
    client.withTransaction { tx ->
        // ...
    }.getOrThrow() // unsafe
}
```

`OutcomeUnknown` means the managed database transaction may have committed.
`NotCommitted` confirms only that the managed transaction did not commit; the
block may already have performed external side effects or writes through
another client that the transaction could not roll back. Callers may retry only
when the block and every side effect are deliberately idempotent or otherwise
retry-safe. The runtime KDoc for transaction `getOrThrow()` must carry this warning
prominently.

This must also coordinate with
[Transactional Graph Changesets](../../possible-features/mutation/transactional-graph-changesets.md).

## Ignored Results And Future Must-Use Enforcement

Canonical read, mutation, and transaction results are semantically must-use.
Ignoring one discards privacy, validation, failure, and possibly commit-state
information. Operations remain eager: ignoring a result does not prevent its
database work, turn it into a throwing call, or roll back an enclosing
transaction.

The initial implementation does not enable Kotlin's experimental unused-return-
value checker in consuming projects and does not emit experimental
`@MustUseReturnValues` annotations. Requiring every application to enable an
experimental compiler feature is not part of this RFC.

Generated result-bearing terminals should nevertheless remain organized so a
stable must-use annotation can later be applied at the supported declaration,
class, or file scope without renaming terminals or changing their result types.
Once Kotlin stabilizes the feature and EntKt's minimum supported Kotlin version
includes it, EntKt should:

1. mark canonical read, mutation, and transaction terminals as must-use;
2. configure the stable checker through the EntKt Gradle plugin where needed;
3. document Kotlin's stable explicit-discard syntax for callers that
   intentionally ignore a result; and
4. record the new diagnostic in the breaking-changes log because projects that
   treat warnings as errors may experience it as a source build break.

Must-use enforcement is a future compile-time safeguard, not part of the
runtime result contract. Its later addition must not change execution or error
semantics.

## Exception Capture Boundary

`Failed` is exhaustive for ordinary exceptions raised while a canonical
result-bearing terminal executes; it is not a catch-all for every thrown
`Throwable`. Read terminals preserve the original exception in
`ReadResult.Failed`. Mutation terminals use an `EntMutationException` carrying
the most accurate known `MutationWriteState`; transaction terminals use
`TransactionFailureState`. Hooks, validators, normalizers, derivations, and
privacy rules keep their natural callback signatures; application code does
not construct or return `Failed` itself.

Expected mutation decisions become typed exceptions in
`MutationResult.Failed`: validation uses `EntValidationException`, mutation
privacy uses `EntMutationPrivacyDeniedException`, and recognized constraints or
conflicts use their corresponding typed exceptions. Any exception thrown across
an application callback boundary becomes the cause of
`EntUnexpectedMutationException` with EntKt's current state, even if application
code threw an EntKt exception type. Only EntKt's own positional classification
points may place a typed framework failure directly in `MutationResult.Failed`.

The result boundary begins when a canonical terminal is invoked. Consequently:

- an ordinary exception from I/O, conversion, an application extension point,
  an unavailable capability discovered during execution, or an EntKt
  invariant failure reached during execution becomes `Failed`
- argument and state validation performed by non-result-bearing builder or DSL
  methods throws immediately
- generation-time schema and metadata failures throw because an operation has
  not begun
- `CancellationException` is rethrown so structured cancellation works, and
  JVM `Error`s propagate because EntKt catches `Exception`, not `Throwable`

This boundary gives callers one ordinary failure channel for an executed
operation without converting unrelated Kotlin programming and configuration
errors into ORM results.

## Privacy And Information Disclosure

`ReadResult.Failed(EntPrivacyDeniedException(...))` is useful inside trusted
application code, but it proves more than `ReadResult.Success(null)`: it says
some selected row existed and was denied.
Applications must not expose that distinction to untrusted clients unless
their security model permits it.

Therefore:

- `PrivacyDenial.entityKey` and `reason` are trusted diagnostic data; application
  boundaries must replace them with a public-safe error
- application boundaries that make root absence and denial indistinguishable
  may use `visibleOrNull()` or map that state explicitly with `when`
- logs and tracing may retain richer diagnostic context under existing
  redaction rules
- denial payloads contain the stable entity key needed for correlation but must
  never contain the hydrated entity or a non-key field value that LOAD privacy
  withheld

The canonical result preserves framework truth; it does not decide what an
HTTP or GraphQL boundary is allowed to reveal.

## Generated API And Implementation Shape

Code generation should emit only canonical operation methods. Read and
mutation result types, typed exceptions, and generic projection and composition
functions belong in the runtime module.

All projections must delegate to one execution path:

```text
generated operation -> internal execution -> exhaustive result -> projection
```

There must not be parallel query implementations for throwing, nullable, and
structured forms. Tests should be able to prove that projecting a result
does not perform another driver call.

Generated `OrError`, `OrThrow`, and nullable projection variants are removed
when the canonical result API lands. EntKt does not retain deprecated aliases:
throwing, nullable privacy, and structured handling are expressed through the
runtime projections on the canonical operation result.

The initial generated surface exposes `findById(): ReadResult<Entity?>` and
does not add a parallel strict lookup. A strict convenience may be added later
under an explicitly non-null name if real call sites justify more generated
surface.

This RFC may land before
[Privacy-Safe Query Surfaces](../../possible-features/privacy-validation/privacy-safe-query-surfaces.md).
During that temporary ordering, raw terminals remain nameable through a
privacy-rule client and their existing runtime capability rejection is captured
as `ReadResult.Failed(IllegalStateException)`. That is transitional
compatibility, not the intended privacy API: the separate RFC removes those
terminals from the privacy-safe type entirely. Neither RFC is an implementation
prerequisite for the other.

## Migration Plan

1. Introduce the runtime result types, typed operation exceptions, and generic
   projections alongside the legacy result API so intermediate implementation
   commits remain buildable.
2. Implement and test canonical read execution, including strict collection
   denial, `visibleOrNull()`, and eager-edge denial handling.
3. Implement and test canonical mutation execution, exception mapping, and
   commit-boundary semantics, including the revised driver exception
   classification contract.
4. Implement and test `TransactionResult` and transactional `orRollback()`
   composition alongside the existing transaction APIs and migrate each driver
   to structured transaction outcomes.
5. In one source-breaking migration, switch every generated data operation and
   the transaction entry point to the canonical API; remove the generated
   `OrThrow`, nullable, and `OrError` variants, `withTransactionOrError()`, and
   their legacy composition helpers.
6. Once no generated or handwritten code references them, remove `EntResult`,
   `EntError`, and `EntException.error`.
7. Update documentation examples and add a newest-first entry to
   `docs/breaking-changes/index.md` covering the complete public contract
   change and its canonical replacements.

For example, the current `visibleByIdOrNull(id)` behavior migrates to
`findById(id).visibleOrNull().getOrThrow()` without introducing another
database terminal.

Keeping the old and new runtime types side by side during implementation is
only an internal sequencing technique. Because EntKt is pre-stable, the
released API has no compatibility window or deprecated generated surface.

### Breaking Changes Log Requirement

The implementation must add one consolidated newest-first entry to
`docs/breaking-changes/index.md`. It must cover:

- removed generated read terminals and their canonical replacements
- removed generated mutation terminals and the `save()` versus `saveAndLoad()`
  split
- `ReadResult`, `MutationResult`, and `TransactionResult` using
  `getOrThrow()` as their sole throwing projection, with no `orThrow()` alias
- `withTransaction()` returning `TransactionResult`, removal of
  `withTransactionOrError()`, and replacement of `bind()` with `orRollback()`
- transaction blocks receiving `EntTransactionClient`, whose surface omits
  `withTransaction()` so nested client transactions do not compile, while
  nested driver calls throw `NestedTransactionUnsupportedException`
- removal of `EntResult`, `EntError`, and `EntException.error`
- structured failure inspection moving from nested `EntError` payloads to
  direct properties on the corresponding typed exceptions
- mutation failures moving to sealed `EntMutationException` with
  `MutationWriteState`, including direct throwing of the stored mutation
  exception and `EntUnexpectedMutationException` for unclassified failures
- `Driver.withTransaction()` changing to `DriverTransactionResult` with the
  narrowed `TransactionFailureState`, and `Driver.classifyException()` being
  replaced by mutation-only `Driver.classifyMutationException()` returning a
  state-bearing `EntMutationException?`
- mutation state names changing to `NotPersisted`, `TransactionPending`,
  `Committed`, and `PersistenceUnknown`, while transaction failure state uses
  only `NotCommitted` and `OutcomeUnknown`
- assignment-free updates changing from `NoChanges` to successful completion
- ordinary exceptions raised during canonical terminal execution becoming
  `Failed`
- removal of privacy-scanning collection terminals and their overfetch behavior
- strict eager-edge privacy failure and the explicit `filterVisible()` opt-in

The entry should use short before-and-after examples for the common read,
mutation, privacy-as-absence, and transaction migrations. It need not enumerate
every generated method separately when one family-level mapping is unambiguous.

## Alternatives Considered

### Keep Generated Result Variants

This is implemented and explicit at the call site, but it multiplies methods
and combines execution posture with representation. It remains the baseline if
the migration cost of this RFC is not justified.

### Use One Universal `EntResult<T>`

A universal success/error container with one shared error payload is simpler
internally, but it gives reads and mutations the same failure domain and cannot
encode committed-but-hidden writes structurally. This proposal reuses the
successful-value shape while keeping read and mutation failure algebras
separate.

### Make Thrown Exceptions Canonical

Exceptions keep happy-path signatures small, but callers cannot exhaustively
inspect failures without catching, and nullable helpers must decide which
exceptions to erase. This proposal stores mutation exceptions in
`MutationResult.Failed` and keeps throwing available as a projection.

### Make Bare Nullable Reads Canonical

Bare `T?` is pleasant for simple lookup code, but it permanently loses the
difference between absence, privacy denial, and failure. `ReadResult<T?>`
avoids that loss: `Success(null)` is authoritative absence, while denial and
operational failure remain distinguishable through the exception stored in
`Failed`.

## Test Requirements

Before implementation, add or update tests for:

- application code without `@OptIn(EntktInternal::class)` cannot call the
  constructors, `copy()` methods, or guarded factories of `ReadResult.Failed`,
  `MutationResult.Failed`, or `TransactionResult.Failed`
- generated code can construct each framework failure through its
  `@EntktInternal`-guarded `failedForInternalUse()` factory
- opted-in application tests can construct each `Failed` variant through the
  guarded factory without making its constructor or `copy()` public
- application code cannot define a direct `EntMutationException` subtype, and
  a `when` over its framework-owned subtypes can be exhaustive
- `Success` remains publicly constructible, while third-party driver code can
  publicly construct both `DriverTransactionResult` variants
- exhaustive read handling for `Success` and `Failed`, including typed privacy
  failure
- root LOAD denial becomes
  `ReadResult.Failed(EntPrivacyDeniedException(Root, ...))`, and
  `getOrThrow()` throws that stored exception directly
- `EntPrivacyDeniedException.denials` is non-empty; a singular read LOAD denial
  contains exactly one keyed denial
- a `saveAndLoad()` LOAD denial uses `EntMutationPrivacyDeniedException`, carries
  the returned entity key when available, and reports EntKt's current mutation
  write state with `operation = EntOperation.LOAD`
- a pre-write mutation privacy rejection uses the denied mutation operation;
  `UPDATE + NotPersisted` is distinguishable from `LOAD + NotPersisted` after an
  allowed no-op update
- `ReadResult.Failed` preserves the original operational exception without a
  universal `EntError` or `ReadFailure` wrapper
- framework-created expected-path exceptions retain ordinary non-empty JVM
  stack traces when inspected directly or thrown by a projection
- target-absence, mutation-privacy, validation, constraint, conflict, and query-
  rejection exceptions expose the documented direct structured properties
  without wrapping an `EntError`
- validation and LOAD-denial lists enforce their documented non-empty
  invariants, create privacy denial permits a missing entity key, and a
  recognized constraint preserves its driver exception as the cause
- each existing read-side `EntError` maps to the documented `ReadResult` state
- read-side driver, materialization, interceptor-rejection, privacy, and final
  terminal catch boundaries preserve the original ordinary exception directly;
  mutation boundaries preserve an unclassified exception as the cause of
  `EntUnexpectedMutationException`
- read execution never calls `classifyMutationException()`; mutation driver
  classification returns either a state-bearing `EntMutationException` or
  `null`, with no parallel state field
- recognized driver constraints return `EntConstraintViolationException` with
  `NotPersisted`, known uncertain outcomes return
  `EntUnexpectedMutationException` with `PersistenceUnknown`, and an
  unclassified driver-call exception falls back to generated phase tracking
- `explain*()` continues to return `QueryPlan` directly, interceptor rejection
  remains `QueryPlan.rejected`, and `requireNotRejected()` retains its explicit
  throwing behavior
- singular lookup absence is `Success(null)`
- collection result types are non-null and `all()` returns
  `Success(emptyList())` for no rows
- strict-posture `firstOrNull()` never scans beyond its selected SQL window
- strict `all()` never returns a partial list after denial
- strict `all()` evaluates the selected window and reports every denied root
  row as an ordered, non-empty list of `PrivacyDenial` values containing stable
  entity keys but no hydrated entities or non-key fields
- a LOAD-denied eager target makes the entire root terminal
  `ReadResult.Failed(EntPrivacyDeniedException(EagerEdge(path), ...))` by
  default; no partial graph is returned and the target is not silently omitted
- an eager-denial path identifies every traversed source type, edge name, and
  target type without retaining hydrated entities, field values, or viewer data
- strict eager privacy fails on the first denied target in the documented eager
  traversal order and reports exactly that one keyed denial without executing
  later eager work solely for diagnostic aggregation
- `filterVisible()` on a to-one eager edge produces `EdgeState.Loaded(null)`, and
  on a to-many eager edge omits denied targets without scanning replacements
- `filterVisible()` applies only to its eager-edge handle, is not inherited by nested
  eager loads, and never changes root LOAD-denial behavior
- `filterVisible()` does not suppress eager-query rejection or ordinary privacy,
  driver, or materialization exceptions
- every generated `with<Edge> {}` returns an edge-specific `EagerLoad` handle;
  ignoring it retains strict privacy, while `filterVisible()` returns the
  parent query for continued fluent composition
- eager-edge privacy denial does not introduce a privacy-specific edge load
  state
- an ordinary exception from any strict `all()` privacy evaluation is returned
  as `Failed(exception)` instead of an incomplete denial aggregate
- `getOrThrow()` preserves successful absence but throws privacy and
  operational failures
- `visibleOrNull()` maps `Failed(EntPrivacyDeniedException(Root, ...))` to
  `Success(null)`, preserves successful presence and absence, and propagates
  eager-edge denial and every other `Failed` unchanged
- adding an eager load cannot make `visibleOrNull()` convert a visible root to
  apparent absence; eager denial remains failed unless that edge explicitly
  uses `filterVisible()`
- `visibleOrNull()` performs no driver calls, privacy re-evaluation, or scanning
- ordinary exceptions from mutation hooks, validators, privacy rules, other
  application extension points, and framework-invariant failures reached
  during terminal execution become `Failed(EntUnexpectedMutationException(...))`
  with the current state and the original exception as its cause
- cancellation and JVM errors still propagate from read, mutation, and
  transaction terminals
- pre-terminal builder validation and generation-time metadata failures still
  throw normally
- target absence, validation rejection, recognized constraints, and conflicts
  become `MutationResult.Failed` with typed exceptions that hardcode
  `NotPersisted`
- mutation privacy rejection before persistence uses
  `EntMutationPrivacyDeniedException` with `NotPersisted`; returned-entity
  privacy denial uses the same type with EntKt's current state
- `delete(entity)` treats the entity as an ID handle and returns `Success(Unit)`
  whether it deletes the freshly reloaded row or finds it already absent
- `deleteById(id)` returns `Success(true)` only when its final delete removes the
  row and `Success(false)` when the row is absent before or during the operation
- an already absent delete target does not run DELETE privacy, validation, or
  lifecycle callbacks; a target that disappears after reload may have run
  before-delete callbacks, but after-delete callbacks do not run
- an assignment-free update returns `MutationResult.Success` only after
  establishing that its target exists, skips persist and post-persist
  callbacks, and has no `Unchanged` branch
- explicitly assigned values are written even when they equal the current row
- pre-write privacy and validation failures do not persist data
- `Success` inside a caller-owned transaction does not claim that the outer
  transaction committed
- `save()` returns `MutationResult.Success(Unit)` without applying returned-entity
  LOAD privacy
- `saveAndLoad()` returns a non-null entity on `Success`
- `createMany()` uses one transaction across every internal batch, row write,
  and write-side lifecycle callback; any pre-completion failure leaves no
  committed subset after a confirmed rollback
- `createMany()` has no implicit conflict-skipping or per-input partial-success
  result; any future partial contract uses a separately named operation
- failure to materialize or disclose `createMany()`'s returned entities after
  all writes and write-side lifecycle work succeed commits either the entire
  EntKt-owned batch or none of it, and reports `Committed` or
  `PersistenceUnknown` as appropriate
- `createMany()` materializes and applies LOAD privacy to returned entities in
  input/result order, fails on the first return-processing error or denial,
  reports that one entity with `operation = EntOperation.LOAD`, and never
  returns a partial list or evaluates later entities solely to aggregate
  diagnostics
- `deleteMany()` uses one transaction across candidate selection and
  processing, every row deletion, and write-side lifecycle callback; privacy,
  validation, hook, or driver failure leaves no committed subset after a
  confirmed rollback
- `deleteMany()` does not silently skip a denied or invalid candidate and has no
  per-row partial-success result; preflight aggregation remains a separate
  future feature
- post-write LOAD denial is
  `MutationResult.Failed(EntMutationPrivacyDeniedException(writeState, operation = EntOperation.LOAD, ...))`
  containing the returned entity key and safe diagnostic reason but no
  hydrated entity or non-key field values
- a no-op `saveAndLoad()` whose LOAD check denies returns
  `MutationResult.Failed(EntMutationPrivacyDeniedException(MutationWriteState.NotPersisted, operation = EntOperation.LOAD, ...))`
- a materialization failure after persistence is `MutationResult.Failed` with
  `EntUnexpectedMutationException` carrying accurate write-state metadata and
  preserving the original exception as its cause
- an EntKt-owned `saveAndLoad()` commits a successful write even when
  materialization or LOAD privacy prevents returning the entity, then reports
  a mutation exception whose state is `Committed`
- a caller-owned transaction reports the same return-value failure immediately
  with `TransactionPending`, marks the scope rollback-only, and later reports
  `TransactionResult.Failed(..., TransactionFailureState.NotCommitted)` after
  confirmed rollback or `OutcomeUnknown` if rollback cannot be confirmed
- an uncertain commit during that path reports a mutation exception carrying
  `PersistenceUnknown`
- mutation `getOrThrow()` performs no additional driver calls, and no mutation
  `orNull()` projection is generated or provided by the runtime
- mutation `getOrThrow()` throws the exact stored `EntMutationException`, including
  its `writeState`
- mutation `getOrThrow()` preserves `Committed` and `PersistenceUnknown` on the
  exact thrown exception, and its runtime KDoc warns that thrown mutation
  failures must not be blanket-retried
- an exception thrown by an application callback becomes the cause of
  `EntUnexpectedMutationException` with EntKt's state assessment; a callback-
  thrown `EntValidationException` does not become a typed validation failure
- constraint, conflict, unknown-commit, and existing post-persist lifecycle
  callback failures preserve the documented write state
- neither `PersistenceUnknown` nor `OutcomeUnknown` is retried automatically;
  either invalidates the affected connection and transaction
- `withTransaction()` is the sole canonical transaction entry point and
  returns `TransactionResult`; `.getOrThrow()` performs no additional transaction
  or driver work
- `DriverTransactionResult.Failed`, `TransactionResult.Failed`, and
  `EntTransactionFailedException` use only `TransactionFailureState` for
  transaction-state metadata;
  `TransactionPending` and `Committed` are not representable transaction
  failure states
- `TransactionResult.Failed` does not expose the block value
- `orRollback()` returns a successful read or mutation value, or uses the
  result's stored exception to roll back; it exists only inside the current
  transaction scope
- `orRollback()` does not validate result provenance; only operations executed
  through the provided transaction client are included in the rollback
- a mutation failure produced through the transaction client marks the current
  scope rollback-only even if its result is ignored; when the block returns
  normally, the first recorded failure is returned after rollback and later
  failures are retained as suppressed exceptions
- an exception or `orRollback()` exit from the transaction block is the primary
  transaction failure; distinct mutation failures recorded before that exit are
  retained as suppressed exceptions in encounter order
- a rollback failure is suppressed on the primary failure and reports
  `OutcomeUnknown`; a commit failure is primary and later cleanup failures are
  suppressed on it
- `orRollback()` remains the normal mutation composition style because it
  extracts success and stops dependent code immediately
- a read failure marks the scope rollback-only only when projected through
  `orRollback()`, except that an underlying aborted database transaction is
  always detected at the transaction boundary
- `EntTransactionClient` has no `withTransaction()` member, so nested client
  transactions do not compile; calling `withTransaction()` on a
  transaction-scoped driver throws `NestedTransactionUnsupportedException`
  before the nested block or any nested transaction I/O runs
- `TransactionResult.getOrThrow()` preserves `transactionState` in its wrapper
  exception, exposes the stored failure as the same non-null `exception` used
  as its cause, and performs no additional transaction or driver work
- transaction `getOrThrow()` runtime KDoc warns against blanket retry;
  `NotCommitted` covers only the managed transaction, while `OutcomeUnknown`
  may include a committed database transaction
- ignored read results trigger no result-level runtime detection; ignored
  mutation failures on a transaction client trigger rollback-only protection,
  and compiler must-use enforcement remains deferred until Kotlin stabilizes
  the feature
- generated `OrThrow`, nullable, and `OrError` projection variants are absent
  after migration
- the breaking-changes log lists each removed generated method family and its
  canonical replacement
