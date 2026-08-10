# RFC: Canonical Operation Result Algebra

## Status

Possible future feature. This is not implemented.

If adopted, this RFC would be a breaking successor to the current
[EntKt Result Variants](../../implemented-features/tooling/entkt-result-variants-rfc.md)
design. It proposes one canonical result-bearing operation instead of adding
another family of generated terminals.

## Summary

Make every public database operation return a canonical exhaustive result.
Throwing and nullable behavior become reusable Kotlin projections of
that result rather than separately generated database operations.

For reads:

```kotlin
sealed interface ReadResult<out T> {
    data class Success<T>(val value: T) : ReadResult<T>
    data class Failed(
        val exception: Exception,
    ) : ReadResult<Nothing>
}
```

The success payload carries the operation's cardinality. A singular lookup
uses nullable `T` to distinguish presence from authoritative absence. A
collection uses non-null `List<T>`, so `Success(null)` is not a valid
collection result. `Failed` implements `ReadResult<Nothing>` through
covariance. Nullable success payloads are reserved for
generated lookups of non-null entity types; nullable scalar values need a
distinct representation if EntKt later supports selecting them directly.
LOAD denial is `Failed(EntPrivacyDeniedException(...))`; other read failures
store the original or framework-created typed exception.

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
builder.saveAndGet(): MutationResult<Entity>
```

`save()` reports whether the mutation completed but does not disclose a
returned entity. `saveAndGet()` additionally materializes and applies LOAD
privacy to the entity it returns. Both results provide an `orThrow()`
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
2. **A projection does not perform I/O.** `getOrThrow()`, `map()`, `flatMap()`,
   and `fold()` operate only on an already-produced result.
3. **Privacy-as-absence is an explicit transformation.** `visibleOrNull()`
   changes only the result representation; it performs no I/O and never scans
   beyond the selected SQL window.
4. **Read and mutation states are not forced into one artificial union.**
5. **Canonical terminals capture ordinary exceptions.** Once a result-bearing
   read, mutation, or transaction terminal begins, every ordinary `Exception`
   that crosses that boundary becomes its corresponding `Failed` result. The
   original exception is preserved; callers do not construct `Failed`
   themselves. Cancellation and JVM errors still propagate.
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
- Support idiomatic exhaustive `when` expressions and functional composition.
- Reduce generated API size and documentation burden.

## Non-Goals

- Do not use `kotlin.Result`; its general `getOrNull()` projection would
  silently collapse read failure into absence, and it cannot attach mutation
  write state.
- Do not define bulk-operation partial-success semantics in this RFC.
- Do not define privacy-skipping scans or visible-page filling. Those may be
  added later if concrete application requirements justify the additional
  ordering, cursor, and scan-budget contracts.
- Do not define HTTP, GraphQL, or other untrusted-boundary mappings for privacy
  denials.
- Do not settle cursor and visible-page representation here; the separate
  [Privacy-Aware Visible Pagination](../query/privacy-aware-visible-pagination.md)
  RFC remains independent future exploration.

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
4. A LOAD denial becomes `Failed(EntPrivacyDeniedException(...))`.
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

The replacement is operation-specific: read failures carry a typed
`Exception`, while mutation failures additionally carry `WriteState`. Typed
exceptions retain structured privacy, validation, constraint, conflict, and
target-absence details without duplicating each one as a result variant.

`EntException` remains the common base for framework-created exceptions, but it
no longer exposes `val error: EntError`. Each typed exception retains its
specific payload:

```kotlin
abstract class EntException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class EntPrivacyDeniedException(
    val denials: List<PrivacyDenial>,
) : EntException("LOAD denied for ${denials.size} ${denials.singleOrNull()?.entityType ?: "entities"}") {
    init {
        require(denials.isNotEmpty())
    }
}

```

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
| `ValidationFailed` | `MutationResult.Failed(EntValidationException(...), NotPersisted)` |
| `ConstraintViolation` | `MutationResult.Failed(EntConstraintViolationException(...), NotPersisted)` |
| `Conflict` | `MutationResult.Failed(EntConflictException(...), NotPersisted)` |
| `WriteSucceededLoadDenied` | `MutationResult.Failed(EntPrivacyDeniedException(...), writeState)` |

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
`Failed(EntPrivacyDeniedException(...))`; it does not return a partial list.
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
`Failed(EntPrivacyDeniedException(listOf(denial)))`. EntKt does not silently
scan the second row.

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
Success(value)                         -> Success(value)
Success(null)                          -> Success(null)
Failed(EntPrivacyDeniedException(...)) -> Success(null)
Failed(otherException)                 -> unchanged
```

It performs no I/O, does not re-run LOAD privacy, and never scans another row.
This permits concise privacy-as-absence handling while preserving operational
failure behavior. The transformation intentionally discards the privacy-denial
details; callers that need them must inspect the original `Failed` result:

```kotlin
val user = users.findById(id)
    .visibleOrNull()
    .getOrThrow()
```

The function is defined for nullable singular results. Canonical collection
operations continue to return `ReadResult<List<T>>`; they do not acquire a
nullable success state or silently discard denied rows.

### Functional Operations

At minimum, `ReadResult` should provide:

```kotlin
fun <T, R> ReadResult<T>.map(
    transform: (T) -> R,
): ReadResult<R>

fun <T, R> ReadResult<T>.flatMap(
    transform: (T) -> ReadResult<R>,
): ReadResult<R>

fun <T, R> ReadResult<T>.fold(
    onSuccess: (T) -> R,
    onFailed: (Exception) -> R,
): R
```

`map()` and `flatMap()` transform every `Success` payload and propagate
`Failed` unchanged. For a nullable lookup result, the transform receives `T?`,
so absence remains explicit in Kotlin's type system. These functions are
ordinary in-memory composition and do not imply a transaction or rollback:

```kotlin
when (result) {
    is ReadResult.Success<*> -> handleSuccess(result.value)
    is ReadResult.Failed -> handleFailure(result.exception)
}
```

Exceptions thrown by caller-provided `map()`, `flatMap()`, or `fold()`
callbacks propagate normally; they are not converted to `ReadResult.Failed`.
This matches Kotlin's distinction between `Result.map()` and
`Result.mapCatching()` and avoids presenting an application transformation bug
as a database-operation failure. Explicit `mapCatching()` or
`flatMapCatching()` helpers may be added later if real call sites justify them.

## Eager-Edge Privacy

The initial API is strict for eagerly loaded edges. If LOAD privacy denies any
eagerly loaded target, the root terminal returns
`ReadResult.Failed(EntPrivacyDeniedException(...))`. It does not return a
partially visible graph, silently omit the target, or convert the containing
edge to an unloaded state. Root and eager-target denial therefore have the
same terminal-level outcome: the requested read failed.

Edge load state continues to describe only whether an edge was requested and
loaded. It does not gain a privacy-specific `Denied` state.

A future explicit query option may omit LOAD-denied targets from an eager edge.
That option must be visible at the call site and define both to-one and to-many
semantics, but its API is outside this RFC. Adding it later must not weaken the
strict default.

## Mutation Result

Mutation terminals return one binary result. The success payload distinguishes
an acknowledgement-only write from a write whose entity was requested, while
every unsuccessful terminal returns its exception and known write state:

```kotlin
enum class WriteState {
    NotPersisted,
    Pending,
    Committed,
    Unknown,
}

sealed interface MutationResult<out T> {
    data class Success<T>(
        val value: T,
    ) : MutationResult<T>

    data class Failed(
        val exception: Exception,
        val writeState: WriteState,
    ) : MutationResult<Nothing>
}
```

`Failed` means the terminal did not produce its requested success value. It
does not imply that the database write rolled back. The exception explains why
the requested mutation terminal failed; `writeState` records what EntKt knows
about its database effect so callers can make safe retry decisions from the raw
result.

### Variant Semantics

`Success(value)` means the requested mutation terminal completed. As with
ordinary transactional APIs, success inside a caller-owned transaction means
the mutation is staged in that transaction; it does not claim that the outer
transaction has committed.

For `save()`, the value is `Unit`. For `saveAndGet()`, the value is the
materialized entity:

```kotlin
createBuilder.save(): MutationResult<Unit>
createBuilder.saveAndGet(): MutationResult<Entity>
updateBuilder.save(): MutationResult<Unit>
updateBuilder.saveAndGet(): MutationResult<Entity>
deleteBuilder.save(): MutationResult<Unit>
```

`save()` still performs normalization, validation, mutation privacy,
persistence, and lifecycle work required by the mutation pipeline. It does not
apply returned-entity LOAD privacy because it does not disclose an entity.
Implementations may still hydrate database-generated fields internally when
hooks or persistence bookkeeping require them; that is not part of the public
return contract.

`saveAndGet()` performs the same mutation and additionally materializes the
returned entity and applies post-write LOAD privacy. A successful returned
entity is non-null. Target absence fails with a typed exception rather than a
nullable success payload.

Known mutation conditions become typed exceptions inside `Failed`:

| Condition | Exception | Write state |
|---|---|---|
| update or delete target is absent | `EntTargetAbsentException` | `NotPersisted` |
| mutation privacy denies the operation | `EntMutationPrivacyDeniedException` | `NotPersisted` |
| validation rejects the mutation | `EntValidationException` containing the violations | `NotPersisted` |
| a recognized database constraint is violated and rolled back | `EntConstraintViolationException` containing the violation | `NotPersisted` |
| an expected concurrency or compare-and-set conflict occurs | `EntConflictException` containing the conflict | `NotPersisted` |
| returned-entity LOAD privacy denies `saveAndGet()` | `EntPrivacyDeniedException` | current write state |
| materialization, hook, or driver execution throws | the original exception | current write state |

The first five rows are expected mutation failures, but they do not need
parallel result variants and exception classes describing the same condition.
Their typed exceptions retain the structured payloads callers may need.

A returned-entity LOAD denial uses the LOAD-specific
`EntPrivacyDeniedException`; mutation privacy uses
`EntMutationPrivacyDeniedException`. The distinct names prevent callers from
confusing rejection of the write with denial of the returned entity.
`EntMutationPrivacyDeniedException` retains a mutation-specific denial payload
that identifies the entity, mutation operation, and safe diagnostic reason; it
does not reuse the LOAD-specific `PrivacyDenial` payload.

`Failed(exception, writeState)` does not by itself mean that the database write
failed. The write state distinguishes a no-op or pre-write rejection
(`NotPersisted`), a write staged in a caller-owned transaction (`Pending`), a
committed write (`Committed`), and an uncertain outcome (`Unknown`). The exact
transaction-boundary rules are defined below.

EntKt constructs `Failed`; application callbacks never return it. Hooks,
normalizers, derivations, validators, privacy rules, and other mutation
extension points retain their natural signatures. A validator returns its
ordinary valid/invalid decision and a privacy rule returns its ordinary
allow/deny decision. An expected invalid or denied decision becomes a typed
exception in `Failed(..., NotPersisted)`; an ordinary exception thrown while
EntKt invokes the callback becomes `Failed(exception, currentWriteState)`.

Known failures are classified at their specific execution boundaries so EntKt
can retain the most accurate write state. The terminal also has a final
`Exception` boundary: any unexpected ordinary exception that reaches it,
including an EntKt invariant failure during execution, becomes
`Failed(exception, currentWriteState)`. It rethrows `CancellationException`
before constructing `Failed` and never catches `Throwable`, so JVM `Error`s
propagate unchanged.

Mutation results do not return a receipt or idempotency token. EntKt reports
write-state certainty; applications own idempotency, compensation, and
reconciliation policies through mechanisms such as client-generated IDs,
unique business keys, request-key tables, or durable outboxes. A token returned
after execution could not make an uncertain retry safe because the response
containing it may itself be lost.

### Write State

`WriteState` records what EntKt knows about this mutation's database effect:

- `NotPersisted` means the mutation has no durable effect. It did not execute,
  was rejected before execution, or was rolled back successfully.
- `Pending` means its SQL executed inside a caller-owned transaction that
  remains open. The transaction owner still determines whether it commits.
- `Committed` means EntKt received confirmation that the transaction committed.
- `Unknown` means EntKt cannot determine whether it committed, normally because
  the driver connection failed during commit or outcome confirmation.

`Failed` always carries a `WriteState`, including expected pre-write failures.
Target absence, mutation privacy denial, validation rejection, and a recognized
constraint or conflict that is confirmed rolled back use `NotPersisted`.

A successful terminal does not return a write state: success in a caller-owned
transaction has the ordinary transactional meaning of successful staging,
while success from an EntKt-owned transaction means its commit completed.

Returning `Failed(..., WriteState.Pending)` does not roll back a transaction
EntKt does not own. The caller must propagate it through the transactional
`orRollback()` API, throw, or explicitly roll back. A result value alone does not
control its surrounding transaction.

EntKt must not automatically retry `Unknown`. It preserves the original
exception, treats the affected connection and transaction as unusable, and
leaves reconciliation or idempotent retry to the application.

### No-Op Updates

No separate `Unchanged` outcome is part of the algebra. An existing-target
update that contains no assignments after normalization, pre-write hooks,
privacy, validation, and derivation completes as `Success`. `save()`
returns `Unit`; `saveAndGet()` returns the current entity after applying its
ordinary LOAD privacy contract. A missing target produces
`Failed(EntTargetAbsentException(...), NotPersisted)`, so an empty update must
still establish whether its target exists.

If that LOAD check denies the returned entity, `saveAndGet()` returns
`Failed(EntPrivacyDeniedException(...), WriteState.NotPersisted)`.
`NotPersisted` is accurate because the successful no-op save performed no
database write.

The persist phase and post-persist or `afterCommit` callbacks do not run when
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
   returning `Failed(EntConstraintViolationException(...), NotPersisted)`.
3. `Success` inside a caller-owned transaction reports successful staging, not
   commitment of that transaction. Commit failure is surfaced by the owning
   transaction boundary.
4. A driver failure whose commit result is genuinely unknown must use
   `Failed(..., WriteState.Unknown)`; it cannot be reported as definitely
   uncommitted.
5. A synchronous post-commit hook failure uses
   `Failed(..., WriteState.Committed)`. Failure to materialize or LOAD the
   value requested by `saveAndGet()` also uses `Failed(exception, writeState)`;
   the exception preserves the reason and `writeState` preserves the database
   effect.

For `saveAndGet()`, successful persistence is not undone merely because the
requested return value cannot be materialized or disclosed. When EntKt owns the
transaction and the write has executed successfully, it commits the usable
transaction and returns `Failed(exception, Committed)`. When the caller owns
the transaction, EntKt returns `Failed(exception, Pending)` without deciding
whether the caller should commit or roll back; `orRollback()` remains the
explicit rollback choice. An assignment-free update returns
`Failed(exception, NotPersisted)` because no write occurred. If a connection or
commit failure prevents EntKt from determining the outcome, the result is
`Failed(exception, Unknown)` rather than an assumed committed or uncommitted
state.

The relationship to
[Structured Mutation Pipeline](../mutation/structured-mutation-pipeline.md),
especially `afterCommit`, must define how callbacks deferred by a caller-owned
transaction surface from transaction completion; an earlier mutation result
cannot be changed after it has been returned.

## Mutation Projections

Canonical writes return their exhaustive result:

```kotlin
client.users.create { ... }.save(): MutationResult<Unit>
client.users.create { ... }.saveAndGet(): MutationResult<User>
client.users.update(id) { ... }.save(): MutationResult<Unit>
client.users.update(id) { ... }.saveAndGet(): MutationResult<User>
```

The runtime module provides one projection for every mutation result:

```kotlin
fun <T> MutationResult<T>.orThrow(): T
```

Its value projection is deliberately simple:

```text
Success(value) -> return value
Failed         -> throw
```

Typical use is concise:

```kotlin
client.users.create { ... }.save().orThrow()
val user = client.users.create { ... }.saveAndGet().orThrow()
```

`orThrow()` returns `Success.value` or throws an
`EntMutationFailedException` that retains `writeState` and uses the stored
exception as its cause. The wrapper is required because rethrowing the stored
exception alone would discard whether the mutation committed or has an unknown
outcome. Callers that inspect the raw result can access both values without
projection.

```kotlin
class EntMutationFailedException(
    val writeState: WriteState,
    cause: Exception,
) : EntException("Mutation failed with write state $writeState", cause)
```

There is no mutation `orNull()`. Collapsing every failure to null would hide
materially different states, including a definitely unpersisted write, a
committed write whose callback failed, and an unknown commit outcome. Clients
must inspect the raw result or choose the explicit throwing projection.

This direction supersedes [Explicit Save Terminals](../mutation/explicit-save-terminals.md)
if adopted. That RFC improves the existing result-variant design by preferring
`saveOrThrow()` and `saveOrError()`; this RFC instead makes plain `save()`
exhaustive and moves those behaviors to projections.

No additional privacy-specific mutation projection is part of the initial
API. If repeated handling of LOAD-denied `Failed` results emerges in
application code, a narrowly named helper can be considered later without
changing the `orThrow()` contract.

## Transactional Composition

Result composition and transaction composition are related but not
interchangeable.

`flatMap()` only propagates a result. It does not establish a transaction and
cannot promise rollback of earlier writes.

EntKt should retain an explicitly transactional scope for multi-operation
composition. Transactions use a deliberately small algebra because a block may
combine reads, every mutation kind, and arbitrary application code:

```kotlin
sealed interface TransactionResult<out T> {
    data class Success<T>(
        val value: T,
    ) : TransactionResult<T>

    data class Failed(
        val exception: Exception,
        val writeState: WriteState,
    ) : TransactionResult<Nothing>
}

class TransactionScope internal constructor() {
    fun <T> ReadResult<T>.orRollback(): T
    fun <T> MutationResult<T>.orRollback(): T
}

fun <T> EntClient.withTransaction(
    block: TransactionScope.(EntClient) -> T,
): TransactionResult<T>
```

The public client has one canonical transaction entry point:

```kotlin
client.withTransaction { tx ->
    val user = tx.users.create { ... }.saveAndGet().orRollback()
    val note = tx.notes.create {
        ownerId = user.id
    }.saveAndGet().orRollback()
    note
}: TransactionResult<Note>
```

There is no parallel `transactionResult()` or `withTransactionOrError()` API.
`withTransaction()` returns the exhaustive result; callers wanting throwing
behavior project it explicitly:

```kotlin
val note = client.withTransaction { tx ->
    // ...
}.orThrow()
```

There is no transaction `orNull()` projection.

`orRollback()` returns a successful operation value. For either
`ReadResult.Failed` or `MutationResult.Failed`, it uses the stored exception and
stops the block so the transaction boundary can roll back. The original
operation result remains available to callers that handle it outside
`orRollback()`.

`orRollback()` is a control-flow operation on the currently executing
transaction. It does not claim that the operation producing its receiver ran
in that transaction, and it does not carry or validate result provenance. Only
operations executed through the `tx` client participate in the transaction's
atomic commit or rollback:

```kotlin
client.withTransaction { tx ->
    tx.users.create { ... }.saveAndGet().orRollback() // transactional
    client.audit.create { ... }.save().orRollback()   // independently executed
}
```

In the second call, a failure aborts the current transaction, but any database
effect independently committed through `client` is not undone. Applications
must use the provided `tx` client for operations that must be atomic. Preventing
mixed-client execution before I/O would require a separate transaction-safety
feature; result-level provenance checking would occur too late to provide that
guarantee.

After the block stops, `withTransaction()` rolls back. A confirmed rollback
returns `TransactionResult.Failed(exception, NotPersisted)`. If EntKt cannot
confirm the rollback or commit outcome, it returns `Failed(exception, Unknown)`
and preserves both the triggering exception and transaction failure context.
An ordinary application exception thrown directly by the transaction block
follows the same rollback rules.

Returning or ignoring an operation failure without calling `orRollback()` does
not abort the transaction. This is intentional: the caller may choose to accept
a failed optional operation and commit other writes. `orRollback()` is the
explicit statement that this operation is required for the transaction.

The transaction boundary owns commit and therefore owns failures that occur
while committing or running deferred `afterCommit` callbacks. A synchronous
`afterCommit` exception from a standalone mutation is
`MutationResult.Failed(exception, Committed)`. When an outer transaction owns
the commit, the earlier mutation result remains a successful pending operation
and `withTransaction()` returns
`TransactionResult.Failed(exception, Committed)`. It cannot retroactively
change a mutation result that was already returned inside the block.

When several `afterCommit` callbacks are registered, EntKt runs all of them in
registration order even after one fails. After the final callback, it reports
one aggregate `EntAfterCommitException` containing the stable callback name and
exception for every failure. The first failure is the aggregate exception's
cause and later failures are also attached as suppressed exceptions for
ordinary JVM diagnostics. Callbacks that depend on one another belong in one
callback so the application can define its own fail-fast sequence; one
callback's failure does not silently prevent unrelated committed-side work.

`TransactionResult.Failed` deliberately does not return the block's value, even
when its state is `Committed`; the requested transaction terminal failed.
`Pending` is never valid on a completed `TransactionResult`. `orRollback()` is
available only inside a transaction scope because outside it there is no
current transaction it could roll back.

`TransactionResult.orThrow()` performs no I/O. It returns `Success.value` or
throws an `EntTransactionFailedException` that retains `writeState` and uses the
stored exception as its cause. The wrapper is required because rethrowing the
stored exception alone would discard whether the transaction committed or has
an unknown outcome.

This must also coordinate with
[Transaction Options And Savepoints](../mutation/transaction-options-savepoints.md)
and [Transactional Graph Changesets](../mutation/transactional-graph-changesets.md).

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
`ReadResult.Failed`. Mutation and transaction terminals preserve it alongside
the most accurate known `WriteState`. Hooks, validators, normalizers,
derivations, and privacy rules keep their natural callback signatures;
application code does not construct or return `Failed` itself.

Expected mutation decisions become typed exceptions in
`MutationResult.Failed`: validation uses `EntValidationException`, mutation
privacy uses `EntMutationPrivacyDeniedException`, and recognized constraints or
conflicts use their corresponding typed exceptions. An exception thrown by an
application callback is stored directly with the current write state.

The result boundary begins when a canonical terminal is invoked. Consequently:

- an ordinary exception from I/O, conversion, an application extension point,
  an unavailable capability discovered during execution, or an EntKt
  invariant failure reached during execution becomes `Failed`
- argument and state validation performed by non-result-bearing builder or DSL
  methods throws immediately
- generation-time schema and metadata failures throw because an operation has
  not begun
- exceptions thrown later by caller-provided `map()`, `flatMap()`, or `fold()`
  transformations propagate normally because those transformations are not
  EntKt operation execution
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
- application boundaries that make absence and denial indistinguishable must
  map that state explicitly with `when` or `fold()`
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

## Migration Plan

1. Introduce runtime result types, typed operation exceptions, and generic
   projections; retire universal `EntResult` / `EntError` operation paths.
2. Define read semantics completely, including strict collection denial and
   the `visibleOrNull()` privacy-as-absence transformation.
3. Define mutation exception mappings and commit-boundary semantics.
4. Change generated read and mutation methods to return canonical results and
   remove the generated `OrThrow`, nullable, and `OrError` variants in the same
   breaking change.
5. Update transaction scopes so `orRollback()` consumes the new
   operation results.
6. Update documentation examples and add a newest-first entry to
   `docs/breaking-changes/index.md` listing the removed methods and their
   canonical replacements.

For example, the current `visibleByIdOrNull(id)` behavior migrates to
`findById(id).visibleOrNull().getOrThrow()` without introducing another
database terminal.

Because EntKt is pre-stable, there is no compatibility window or deprecated
generated surface.

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

- exhaustive read handling for `Success` and `Failed`, including typed privacy
  failure
- LOAD denial becomes `ReadResult.Failed(EntPrivacyDeniedException(...))`, and
  `getOrThrow()` throws that stored exception directly
- `EntPrivacyDeniedException.denials` is non-empty; a singular read or
  `saveAndGet()` LOAD denial contains exactly one keyed denial
- `ReadResult.Failed` preserves the original operational exception without a
  universal `EntError` or `ReadFailure` wrapper
- each existing read-side `EntError` maps to the documented `ReadResult` state
- driver, materialization, interceptor-rejection, privacy, and final terminal
  catch boundaries preserve the original ordinary exception
- singular lookup absence is `Success(null)`
- collection result types are non-null and `all()` returns
  `Success(emptyList())` for no rows
- strict-posture `firstOrNull()` never scans beyond its selected SQL window
- strict `all()` never returns a partial list after denial
- strict `all()` evaluates the selected window and reports every denied root
  row as an ordered, non-empty list of `PrivacyDenial` values containing stable
  entity keys but no hydrated entities or non-key fields
- a LOAD-denied eager target makes the entire root terminal
  `ReadResult.Failed(EntPrivacyDeniedException(...))`; no partial graph is
  returned and the target is not silently omitted
- eager-edge privacy denial does not introduce a privacy-specific edge load
  state
- an ordinary exception from any strict `all()` privacy evaluation is returned
  as `Failed(exception)` instead of an incomplete denial aggregate
- `getOrThrow()` preserves successful absence but throws privacy and
  operational failures
- `visibleOrNull()` maps `Failed(EntPrivacyDeniedException(...))` to
  `Success(null)`, preserves successful presence and absence, and propagates
  every other `Failed` unchanged
- `visibleOrNull()` performs no driver calls, privacy re-evaluation, or scanning
- `map()`, `flatMap()`, and `fold()` preserve `Failed` and its stored exception
- exceptions thrown by `map()`, `flatMap()`, or `fold()` callbacks propagate
  directly rather than becoming `ReadResult.Failed`
- ordinary exceptions from mutation hooks, validators, privacy rules, other
  application extension points, and framework-invariant failures reached
  during terminal execution become `Failed` with the current write state
- cancellation and JVM errors still propagate from read, mutation, and
  transaction terminals
- pre-terminal builder validation and generation-time metadata failures still
  throw normally
- target absence, mutation privacy denial, validation rejection, recognized
  constraints, and conflicts become `MutationResult.Failed` with their typed
  exception and `NotPersisted`
- an assignment-free update returns `MutationResult.Success` only after
  establishing that its target exists, skips persist and post-persist
  callbacks, and has no `Unchanged` branch
- explicitly assigned values are written even when they equal the current row
- pre-write privacy and validation failures do not persist data
- `Success` inside a caller-owned transaction does not claim that the outer
  transaction committed
- `save()` returns `MutationResult.Success(Unit)` without applying returned-entity
  LOAD privacy
- `saveAndGet()` returns a non-null entity on `Success`
- post-write LOAD denial is
  `MutationResult.Failed(EntPrivacyDeniedException(...), writeState)` with no
  LOAD-denied fields or identifiers
- a no-op `saveAndGet()` whose LOAD check denies returns
  `MutationResult.Failed(EntPrivacyDeniedException(...), NotPersisted)`
- a materialization failure after persistence is `MutationResult.Failed` with
  the original exception and accurate write-state metadata
- an EntKt-owned `saveAndGet()` commits a successful write even when
  materialization or LOAD privacy prevents returning the entity, then reports
  `Failed(..., Committed)`
- a caller-owned transaction reports the same return-value failure as
  `Failed(..., Pending)` and commits or rolls back only through the caller's
  transaction decision
- an uncertain commit during that path reports `Failed(..., Unknown)`
- mutation `orThrow()` performs no additional driver calls, and no mutation
  `orNull()` projection is generated or provided by the runtime
- mutation `orThrow()` throws `EntMutationFailedException` with the stored
  exception as its cause and preserves `writeState`
- constraint, conflict, unknown-commit, and post-commit failures preserve the
  documented commit state
- `Unknown` is never retried automatically and invalidates the affected
  connection and transaction
- `withTransaction()` is the sole canonical transaction entry point and
  returns `TransactionResult`; `.orThrow()` performs no additional transaction
  or driver work
- deferred `afterCommit` exceptions surface from the transaction boundary as
  `TransactionResult.Failed(..., Committed)`
- all registered `afterCommit` callbacks run in registration order, and every
  callback failure is retained in one aggregate exception
- `TransactionResult.Failed` never uses `Pending` and does not expose the block
  value, including after a committed callback failure
- `orRollback()` returns a successful read or mutation value, or uses the
  operation's stored or projected exception to roll back; it exists only inside
  the current transaction scope
- `orRollback()` does not validate result provenance; only operations executed
  through the provided transaction client are included in the rollback
- an operation failure that is returned or ignored without `orRollback()` does
  not abort the transaction
- `TransactionResult.orThrow()` preserves `writeState` in its wrapper exception
  and performs no additional transaction or driver work
- ignored canonical results execute normally and trigger no runtime detection;
  compiler must-use enforcement is deferred until Kotlin stabilizes the feature
- generated `OrThrow`, nullable, and `OrError` projection variants are absent
  after migration
- the breaking-changes log lists each removed generated method family and its
  canonical replacement
