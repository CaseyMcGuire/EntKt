# RFC: Result Variants and Structured Operation Errors

## Status

Possible future feature. This is not implemented.

## Summary

Add generated operation variants that make success, absence, privacy denial,
validation failure, constraint failure, and concurrency conflicts explicit.

The core generated variants are:

```kotlin
xOrThrow()
xOrError()
```

`xOrNull()` should be generated only for operations where `null` has a narrow,
obvious meaning: absence, no matching row, or optionally not visible to the
current viewer when the method name says so.

The guiding rule is:

```text
null means “there is no value for this operation,” not “something failed.”
```

This keeps privacy, validation, uniqueness, serialization, and driver failures
visible to callers while still allowing concise optional-read APIs.

## Motivation

EntKt currently has several operation outcomes that are meaningfully different:

- no row exists
- a row exists but the viewer cannot read or mutate it
- input failed field validation
- entity-level validation failed
- a database constraint failed
- an optimistic-locking or relationship-serialization conflict occurred
- the driver or database failed

If all of these are modeled as exceptions, application code can be simple, but
API handlers often need to translate failures into structured HTTP, GraphQL, or
RPC responses.

If all of these are modeled as `null`, important failures disappear. For
example, this is ambiguous and should be avoided:

```kotlin
val user = client.users.create {
    email = "invalid"
}.saveOrNull()

// Did validation fail?
// Was privacy denied?
// Did a unique constraint fail?
// Is the database down?
// The caller cannot know.
```

A typed result API gives application code a stable way to inspect failures
without parsing exception messages or losing error detail.

## Goals

- Provide generated `xOrThrow()` APIs for simple application code.
- Provide generated `xOrError()` APIs for structured error handling.
- Provide `xOrNull()` only where `null` means absence or intentional invisibility.
- Preserve EntKt's privacy and validation guarantees.
- Avoid converting validation, privacy, constraint, conflict, or driver failures
  into `null` by default.
- Make transactional `xOrError()` usage safe and ergonomic.
- Give edge mutation APIs a precise way to report serialization conflicts.

## Non-Goals

- Do not replace all exception-based APIs.
- Do not make every generated operation nullable.
- Do not silently swallow privacy or validation failures.
- Do not require callers to use a functional programming library.
- Do not define the complete database-driver error mapping for every dialect in
  the first version.
- Do not solve every bulk-operation error aggregation problem in V1.

## Terminology

### Absence

The requested value does not exist for the operation. Examples:

- `byId(id)` found no row.
- `first()` found no matching row.
- `deleteById(id)` found no row to delete.

### Invisibility

A row may exist, but LOAD privacy does not allow the current viewer to see it.
Depending on the method, invisibility can either be represented as an error or
as absence.

Method names must make this distinction explicit.

### Failure

The operation could not complete successfully because a rule, invariant,
constraint, conflict, or infrastructure condition prevented it. Examples:

- privacy denied
- validation failed
- unique constraint failed
- serialization conflict
- driver failure

Failures should not be collapsed into `null`.

## Proposed API Family

### Throwing APIs

Throwing APIs return the successful value or throw a structured EntKt exception.
They are the best default for simple service code and tests.

```kotlin
val user: User = client.users.byIdOrThrow(id)

val created: User = client.users.create {
    name = "Alice"
    email = "alice@example.com"
}.saveOrThrow()
```

### Nullable APIs

Nullable APIs return `null` only for absence, or for absence plus invisibility
when the method name explicitly says so.

```kotlin
val user: User? = client.users.byIdOrNull(id)
val visibleUser: User? = client.users.visibleByIdOrNull(id)
val first: User? = client.users.query { where(User.email eq email) }.firstOrNull()
```

Recommended distinction:

```kotlin
client.users.byIdOrNull(id)
// null if no row exists
// privacy denial remains an error

client.users.visibleByIdOrNull(id)
// null if no row exists or the row is not visible to the viewer
```

Mutation `saveOrNull()` should generally not be generated. If it is generated,
it must have a narrow operation-specific meaning, such as “the row being updated
or deleted no longer exists.” It must not hide validation, privacy, constraint,
conflict, or driver failures.

### Result APIs

Result APIs return a generated success/error result. They are intended for API
boundaries, batch operations, libraries, and code that needs to map EntKt
failures into another error model.

```kotlin
when (val result = client.users.byIdOrError(id)) {
    is EntResult.Ok -> result.value
    is EntResult.Err -> when (val error = result.error) {
        is EntError.NotFound -> null
        is EntError.PrivacyDenied -> throw Forbidden(error.reason)
        else -> throw error.toException()
    }
}
```

```kotlin
when (val result = client.users.create {
    name = "Alice"
    email = "alice@example.com"
}.saveOrError()) {
    is EntResult.Ok -> result.value
    is EntResult.Err -> when (val error = result.error) {
        is EntError.ValidationFailed -> respond422(error.violations)
        is EntError.PrivacyDenied -> respond403(error.reason)
        is EntError.ConstraintViolation -> respond409(error)
        is EntError.DriverFailure -> respond500(error)
        else -> respond500(error)
    }
}
```

## Core Result Type

The core result shape should be small and framework-owned.

```kotlin
sealed interface EntResult<out T> {
    data class Ok<T>(val value: T) : EntResult<T>
    data class Err(val error: EntError) : EntResult<Nothing>
}
```

Useful helpers:

```kotlin
fun <T> EntResult<T>.getOrThrow(): T
fun <T> EntResult<T>.getOrNullForAbsenceOnly(): T?
fun <T, R> EntResult<T>.map(transform: (T) -> R): EntResult<R>
fun <T, R> EntResult<T>.flatMap(transform: (T) -> EntResult<R>): EntResult<R>
```

A coroutine-free V1 can still support result composition through `map` and
`flatMap`.

## Structured Error Model

The exact error model can evolve, but V1 should include at least these cases.

```kotlin
enum class EntOperation {
    LOAD,
    QUERY,
    CREATE,
    UPDATE,
    DELETE,
    EDGE_MUTATION,
    AGGREGATE,
}

sealed interface EntError {
    val entity: String
    val operation: EntOperation
    val message: String

    data class NotFound(
        override val entity: String,
        override val operation: EntOperation,
        val id: Any? = null,
        override val message: String = "entity not found",
    ) : EntError

    data class NoChanges(
        override val entity: String,
        override val operation: EntOperation,
        val id: Any? = null,
        override val message: String = "no changes to save",
    ) : EntError

    data class PrivacyDenied(
        override val entity: String,
        override val operation: EntOperation,
        val reason: String,
        override val message: String = reason,
    ) : EntError

    data class ValidationFailed(
        override val entity: String,
        override val operation: EntOperation,
        val violations: List<ValidationViolation>,
        override val message: String = "validation failed",
    ) : EntError

    data class ConstraintViolation(
        override val entity: String,
        override val operation: EntOperation,
        val constraint: String? = null,
        val field: String? = null,
        val code: String? = null,
        override val message: String,
    ) : EntError

    data class Conflict(
        override val entity: String,
        override val operation: EntOperation,
        val code: String? = null,
        override val message: String,
    ) : EntError

    data class DriverFailure(
        override val entity: String,
        override val operation: EntOperation,
        val cause: Throwable,
        override val message: String = cause.message ?: "driver failure",
    ) : EntError
}
```

Validation violations should preserve field and code metadata.

```kotlin
data class ValidationViolation(
    val message: String,
    val field: String? = null,
    val code: String? = null,
)
```

## Exception Mapping

Throwing APIs should be implemented as wrappers over `xOrError()`.

```kotlin
fun UserCreate.saveOrThrow(): User =
    saveOrError().getOrThrow()
```

`EntError` values should map to structured exceptions:

```kotlin
sealed class EntException(
    open val error: EntError,
) : RuntimeException(error.message)

class EntNotFoundException(
    override val error: EntError.NotFound,
) : EntException(error)

class EntNoChangesException(
    override val error: EntError.NoChanges,
) : EntException(error)

class EntPrivacyDeniedException(
    override val error: EntError.PrivacyDenied,
) : EntException(error)

class EntValidationException(
    override val error: EntError.ValidationFailed,
) : EntException(error)

class EntConstraintViolationException(
    override val error: EntError.ConstraintViolation,
) : EntException(error)

class EntConflictException(
    override val error: EntError.Conflict,
) : EntException(error)

class EntDriverException(
    override val error: EntError.DriverFailure,
) : EntException(error)
```

Existing exception types can either be adapted to wrap `EntError`, or retained
with conversion helpers during a compatibility window.

## Read API Semantics

### By ID

```kotlin
client.users.byIdOrThrow(id): User
client.users.byIdOrNull(id): User?
client.users.visibleByIdOrNull(id): User?
client.users.byIdOrError(id): EntResult<User>
```

Recommended behavior:

| Outcome | `byIdOrThrow` | `byIdOrNull` | `visibleByIdOrNull` | `byIdOrError` |
|---|---|---|---|---|
| Row exists and is visible | returns row | returns row | returns row | `Ok(row)` |
| Row does not exist | throws `NotFound` | `null` | `null` | `Err(NotFound)` |
| Row exists but LOAD privacy denies | throws `PrivacyDenied` | throws `PrivacyDenied` | `null` | `Err(PrivacyDenied)` |
| Driver failure | throws `DriverFailure` | throws `DriverFailure` | throws `DriverFailure` | `Err(DriverFailure)` |

### First Row

```kotlin
query.firstOrThrow(): User
query.firstOrNull(): User?
query.firstVisibleOrNull(): User?
query.firstOrError(): EntResult<User>
```

Recommended behavior:

- `firstOrNull()` returns `null` only if no row matches the storage predicate.
- `firstVisibleOrNull()` returns the first visible row if supported by the
  chosen privacy mode, or `null` if none is visible.
- Privacy denial remains explicit unless the method name says `visible`.

### Many Rows

```kotlin
query.allOrThrow(): List<User>
query.allOrError(): EntResult<List<User>>
query.visibleAll(): List<User>
query.visibleAllOrError(): EntResult<List<User>>
```

`allOrThrow()` should preserve the current strict read model: if any matched row
is denied by LOAD privacy, the operation fails.

`visibleAll()` should be a distinct API if EntKt supports silently excluding
unreadable rows.

## Mutation API Semantics

### Create

```kotlin
client.users.create { ... }.saveOrThrow(): User
client.users.create { ... }.saveOrError(): EntResult<User>
```

`saveOrNull()` is not recommended for creates because create failures are not
absence.

| Outcome | `saveOrThrow` | `saveOrError` |
|---|---|---|
| Created successfully | returns entity | `Ok(entity)` |
| Privacy denied | throws `PrivacyDenied` | `Err(PrivacyDenied)` |
| Validation failed | throws `ValidationFailed` | `Err(ValidationFailed)` |
| Unique/FK/check constraint failed | throws `ConstraintViolation` | `Err(ConstraintViolation)` |
| Driver failure | throws `DriverFailure` | `Err(DriverFailure)` |

### Update

```kotlin
client.users.update(user.id) { ... }.saveOrThrow(): User
client.users.update(user.id) { ... }.saveOrError(): EntResult<User>
client.users.update(user.id) { ... }.saveIfExistsOrNull(): User?
```

`saveIfExistsOrNull()` is optional. If generated, `null` means only that the row
being updated no longer exists. Privacy, validation, constraints, conflicts, and
driver failures remain errors.

### Delete

```kotlin
client.users.deleteOrThrow(user): Unit
client.users.deleteOrError(user): EntResult<Unit>
client.users.deleteByIdOrNull(id): Boolean?
client.users.deleteByIdOrError(id): EntResult<Boolean>
```

For `deleteByIdOrError`, returning `Ok(false)` versus `Err(NotFound)` is an open
design choice. Prefer one convention and use it consistently.

A recommended convention:

```kotlin
deleteByIdOrError(id): EntResult<Boolean>
```

where:

- `Ok(true)` means a row was deleted.
- `Ok(false)` means no row existed.
- `Err(...)` means privacy, validation, constraint, conflict, or driver failure.

## Edge Mutation Semantics

Edge mutation APIs should use the same result-family design as scalar writes.

```kotlin
client.posts.update(post.id) {
    tags.add(kotlinTagId)
}.saveOrError()

client.posts.update(post.id) {
    tags.set(listOf(kotlinTagId, ormTagId))
}.saveOrError()
```

Exact set operations need precise concurrency wording.

Recommended wording:

```text
For a given owner id and edge, set(...) is serialized with other relationship
mutations on that same owner-edge. At its serialization point, the relationship
set equals the requested set. Later serialized mutations may change the
relationship again.
```

A serialization or compare-and-set failure should be represented as:

```kotlin
EntError.Conflict(
    entity = "Post",
    operation = EntOperation.EDGE_MUTATION,
    code = "relationship_serialization_conflict",
    message = "could not serialize tags.set(...) for Post.tags",
)
```

This avoids ambiguous wording such as “subject only to later committed writes,”
which can be confused with wall-clock completion order or transaction commit
order.

## Transaction Semantics

Throwing APIs compose naturally with existing transactions:

```kotlin
client.withTransaction { tx ->
    val user = tx.users.create { ... }.saveOrThrow()
    tx.posts.create { authorId = user.id }.saveOrThrow()
}
```

If an exception is thrown, the transaction rolls back.

Result APIs need an explicit transaction helper so callers do not accidentally
commit partial work after receiving `Err`.

Bad pattern:

```kotlin
client.withTransaction { tx ->
    tx.users.create { ... }.saveOrThrow()

    val post = tx.posts.create { ... }.saveOrError()
    if (post is EntResult.Err) return@withTransaction post

    post
}

// The block returned normally, so the transaction may commit earlier writes.
```

Recommended API:

```kotlin
client.withTransactionOrError { tx ->
    val user = tx.users.create { ... }.saveOrError().bind()
    val post = tx.posts.create { authorId = user.id }.saveOrError().bind()
    post
}: EntResult<Post>
```

Inside `withTransactionOrError`, `bind()` should abort the transaction and
return the first `EntError`.

Possible implementation shape:

```kotlin
class EntResultScope internal constructor() {
    fun <T> EntResult<T>.bind(): T = when (this) {
        is EntResult.Ok -> value
        is EntResult.Err -> throw AbortEntResultTransaction(error)
    }
}
```

The framework catches `AbortEntResultTransaction`, rolls back, and returns
`EntResult.Err(error)`.

Alternative non-throwing composition:

```kotlin
client.withTransactionOrError { tx ->
    tx.users.create { ... }.saveOrError().flatMap { user ->
        tx.posts.create { authorId = user.id }.saveOrError()
    }
}
```

## Bulk Operation Semantics

Bulk APIs should avoid flattening all failures into one nullable result.

Possible V1 options:

```kotlin
createManyOrThrow(...): List<User>
createManyOrError(...): EntResult<List<User>>
```

In V1, `createManyOrError` can stop on the first failure and return that error.
If callers need all per-item failures, add an explicit batch result later:

```kotlin
sealed interface EntBatchResult<out T> {
    data class AllOk<T>(val values: List<T>) : EntBatchResult<T>
    data class Partial<T>(
        val successes: List<T>,
        val failures: List<EntBatchFailure>,
    ) : EntBatchResult<T>
}

data class EntBatchFailure(
    val index: Int,
    val error: EntError,
)
```

If bulk operations can partially write data outside a transaction, the docs must
say so explicitly. Callers who need all-or-nothing behavior should use
`withTransactionOrError`.

## Naming Guidelines

Use names that communicate error behavior precisely.

Recommended:

```kotlin
byIdOrThrow(id)
byIdOrNull(id)
visibleByIdOrNull(id)
byIdOrError(id)

firstOrThrow()
firstOrNull()
firstVisibleOrNull()
firstOrError()

allOrThrow()
allOrError()
visibleAll()
visibleAllOrError()

saveOrThrow()
saveOrError()
saveIfExistsOrNull()

deleteOrThrow(entity)
deleteOrError(entity)
deleteByIdOrError(id)
deleteByIdOrNull(id)
```

Avoid broad names with ambiguous failure behavior:

```kotlin
saveOrNull()       // ambiguous for mutations
queryOrNull()      // unclear whether privacy denial is null
trySave()          // unclear whether result is null, Boolean, or error
safeSave()         // unclear what “safe” means
```

## Compatibility Plan

A staged rollout could look like this:

1. Add `EntResult` and `EntError` runtime types.
2. Add conversion helpers from existing exceptions to `EntError`.
3. Generate `xOrError()` wrappers internally around existing throwing paths.
4. Generate `xOrThrow()` wrappers over `xOrError()` for new operations.
5. Add precise `xOrNull()` variants for read absence.
6. Deprecate ambiguous nullable mutation APIs, if any exist.
7. Add `withTransactionOrError`.
8. Extend driver error mapping for database-specific constraint names/codes.

## Test Requirements

Before implementation, add tests for the following.

### Read APIs

- `byIdOrNull` returns `null` for missing rows.
- `byIdOrNull` does not hide privacy denial.
- `visibleByIdOrNull` returns `null` for privacy denial.
- `byIdOrError` returns `Err(NotFound)` for missing rows.
- `byIdOrError` returns `Err(PrivacyDenied)` for denied rows.
- `firstOrNull` returns `null` only for no matching row.
- `firstOrError` distinguishes no match from privacy denial.
- `allOrError` preserves strict read behavior.

### Mutation APIs

- `saveOrError` returns `Err(ValidationFailed)` with all violations.
- `saveOrError` returns `Err(PrivacyDenied)` for denied creates/updates/deletes.
- `saveOrError` maps unique constraint failures to `ConstraintViolation`.
- `saveOrError` maps foreign-key failures to `ConstraintViolation`.
- `saveOrThrow` throws the matching structured exception.
- `saveIfExistsOrNull` returns `null` only when the target row is missing.

### Transactions

- `withTransactionOrError` commits when all bound results are `Ok`.
- `withTransactionOrError` rolls back when a bound result is `Err`.
- `withTransactionOrError` returns the first error.
- Existing `withTransaction` behavior remains unchanged for throwing APIs.

### Edge Mutations

- `set(...)` produces the requested relationship set at its serialization point.
- Concurrent owner-edge mutations either serialize correctly or return
  `Err(Conflict)`.
- Later serialized relationship mutations may change the set again.
- `add`, `remove`, and `set` expose privacy, validation, and constraint errors
  through `saveOrError`.

### Error Mapping

- Existing privacy exceptions convert to `EntError.PrivacyDenied`.
- Existing validation exceptions convert to `EntError.ValidationFailed`.
- Driver failures convert to `EntError.DriverFailure`.
- Postgres unique/FK/check violations include useful constraint metadata where
  available.

## Open Questions

1. Should `byIdOrNull(id)` return `null` for privacy denial, or should only
   `visibleByIdOrNull(id)` do that?

   Recommendation: keep `byIdOrNull` strict and add `visibleByIdOrNull` for the
   invisibility-as-absence case.

2. Should `deleteByIdOrError(id)` return `Ok(false)` or `Err(NotFound)` when no
   row exists?

   Recommendation: return `Ok(false)` if the operation is framed as “attempt to
   delete,” and `Err(NotFound)` if the operation is framed as “delete required
   row.” EntKt may expose both if needed.

3. Should `EntError.DriverFailure` include raw SQL and bind args?

   Recommendation: no by default. Observability hooks can expose redacted query
   details separately. Error objects should avoid leaking sensitive data.

4. Should `EntResult` be named `EntResult`, `EntOutcome`, or `OperationResult`?

   Recommendation: `EntResult` is concise and easy to recognize.

5. Should result APIs be generated for every operation or only selected ones?

   Recommendation: generate them for all public repo/query/mutation operations
   once the error model is stable.

6. Should nullable mutation APIs exist at all?

   Recommendation: avoid broad nullable mutation APIs. Add narrow variants like
   `saveIfExistsOrNull()` only where absence is a meaningful successful outcome.

## Example End State

```kotlin
val result = client.withTransactionOrError { tx ->
    val user = tx.users.byIdOrError(userId).bind()

    val post = tx.posts.create {
        title = input.title
        authorId = user.id
    }.saveOrError().bind()

    tx.posts.update(post.id) {
        tags.set(input.tagIds)
    }.saveOrError().bind()
}

when (result) {
    is EntResult.Ok -> respond200(result.value)
    is EntResult.Err -> when (val error = result.error) {
        is EntError.NotFound -> respond404(error)
        is EntError.PrivacyDenied -> respond403(error)
        is EntError.ValidationFailed -> respond422(error.violations)
        is EntError.ConstraintViolation -> respond409(error)
        is EntError.Conflict -> respond409(error)
        is EntError.DriverFailure -> respond500(error)
    }
}
```

This keeps simple code simple through `xOrThrow()`, keeps optional reads concise
through narrowly defined `xOrNull()`, and gives application boundaries a stable,
typed error surface through `xOrError()`.
