# RFC: Transaction Options And Savepoints

## Status

Possible future feature. This is not implemented.

## Summary

Extend transaction APIs with explicit transaction options and optional
savepoint support.

The current contract is intentionally simple: root `withTransaction` starts a
transaction, commits on success, and rolls back on failure. Its block receives
an `EntTransactionClient` with no `withTransaction` member, while direct nested
driver calls fail before entering the nested block. This RFC would add an
explicit nested-transaction surface alongside named isolation, read-only,
retry, and savepoint options when the driver supports them.

## Motivation

As generated mutations grow, transaction behavior becomes part of the public
mental model. Users need clear answers to:

- is this block read-only?
- which isolation level was requested?
- will nested transaction blocks isolate partial failures?
- is retry behavior owned by entkt or by the application?
- what happens if the driver cannot support the requested behavior?

Today all of that is implicit or unavailable. A small options object can make
transaction behavior explicit without complicating the common path.

## Non-Goals

- Do not change the default `withTransaction { ... }` behavior.
- Do not make nested savepoints the default.
- Do not implement automatic retries for arbitrary non-idempotent mutations in
  V1.
- Do not hide driver limitations.
- Do not require every driver to support every transaction option.

## Proposed API

Keep the existing API:

```kotlin
client.withTransaction { tx ->
    tx.users.create { email = "a@example.com" }.save()
}
```

Add an overload with options:

```kotlin
client.withTransaction(
    TransactionOptions(
        isolation = IsolationLevel.Serializable,
        readOnly = false,
        nested = NestedTransactionMode.Reuse,
    ),
) { tx ->
    tx.users.create { email = "a@example.com" }.save()
}
```

Suggested option model:

```kotlin
data class TransactionOptions(
    val isolation: IsolationLevel? = null,
    val readOnly: Boolean = false,
    val nested: NestedTransactionMode = NestedTransactionMode.Reuse,
    val retry: TransactionRetryPolicy = TransactionRetryPolicy.None,
)

enum class IsolationLevel {
    ReadCommitted,
    RepeatableRead,
    Serializable,
}

enum class NestedTransactionMode {
    Reuse,
    Savepoint,
    Reject,
}
```

`Reuse` runs the nested block in the outer transaction without a savepoint.
`Savepoint` asks the driver to create a savepoint when already inside a
transaction. `Reject` fails if the call is already nested.

## Savepoint Semantics

When a nested transaction uses `Savepoint`, exceptions roll back only to the
savepoint:

```kotlin
client.withTransaction { tx ->
    tx.users.create { email = "owner@example.com" }.save()

    runCatching {
        tx.withTransaction(TransactionOptions(nested = NestedTransactionMode.Savepoint)) { nested ->
            nested.posts.create { title = "draft" }.save()
            error("cancel nested work")
        }
    }

    tx.auditLogs.create { message = "outer transaction still active" }.save()
}
```

The outer transaction should still commit unless the outer block throws.

If the driver does not support savepoints, `NestedTransactionMode.Savepoint`
must fail clearly before entering the nested block.

## Driver Contract

The driver interface can grow capability methods instead of making all drivers
pretend to support the same behavior:

```kotlin
val supportsTransactionOptions: Boolean
val supportsSavepoints: Boolean

fun <T> withTransaction(
    options: TransactionOptions,
    block: (Driver) -> T,
): T
```

Drivers that cannot honor a requested option should fail fast with an error
that names:

- the unsupported option
- the driver
- the nearest supported alternative when obvious

Example:

```text
PostgresDriver does not support nested savepoints outside an active transaction.
Requested nested = Savepoint.
```

See [Driver Capability Matrix](../tooling/driver-capability-matrix.md).

## Retry Policy

V1 should default to no automatic retries:

```kotlin
TransactionRetryPolicy.None
```

If retry support is added, it should be opt-in and constrained to known
retryable database failures such as serialization failures or deadlocks:

```kotlin
TransactionRetryPolicy.OnSerializationFailure(maxAttempts = 3)
```

The block may run more than once, so generated docs and method names must make
that obvious. Hooks, side effects, and external service calls make automatic
retries dangerous unless the application opts in deliberately.

## Diagnostics

Transaction diagnostics should be visible in query/mutation tracing:

```text
transaction:
  isolation: SERIALIZABLE
  readOnly: false
  nested: savepoint sp_entkt_1
```

Unsupported options should surface as configuration errors, not generic SQL
exceptions.

## Test Requirements

Before implementation, add tests for:

- default `withTransaction` behavior is unchanged
- isolation and read-only options are forwarded to supporting drivers
- unsupported options fail before the block runs
- nested `Reuse` runs in the outer transaction without a savepoint
- nested `Reject` fails inside an active transaction
- nested `Savepoint` rolls back only nested writes
- savepoint failures preserve the outer transaction
- retry policy never runs unless explicitly configured
