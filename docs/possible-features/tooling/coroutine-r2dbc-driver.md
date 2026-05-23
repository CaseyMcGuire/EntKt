# RFC: Coroutine And R2DBC Driver Track

## Status

Possible future feature. This is not implemented.

## Summary

Explore an asynchronous driver track built around Kotlin coroutines and R2DBC,
without forcing the existing synchronous JDBC driver API to become async.

The likely shape is a parallel `SuspendDriver` plus generated suspend clients,
not a breaking rewrite of the current `Driver` interface.

## Motivation

Kotlin applications commonly use coroutines for request handling. A synchronous
JDBC-backed entkt client is simple and useful, but coroutine-first applications
eventually need:

- non-blocking database access
- suspend transaction blocks
- cancellation-aware query execution
- integration with coroutine contexts
- async-friendly request-scoped loaders

R2DBC is the natural ecosystem fit for non-blocking SQL access in Kotlin.

## Non-Goals

- Do not remove or deprecate the JDBC `PostgresDriver`.
- Do not make all generated APIs suspend by default.
- Do not mix blocking and non-blocking calls behind one ambiguous interface.
- Do not require R2DBC dependencies in the core runtime.
- Do not promise transparent behavior parity before driver capabilities are
  documented.

## Proposed Module Shape

Keep dependencies isolated:

```text
:runtime              synchronous public runtime contracts
:postgres             JDBC Postgres driver
:runtime-coroutines   coroutine runtime contracts
:postgres-r2dbc       R2DBC Postgres driver
```

Names are tentative. The important point is that coroutine/R2DBC dependencies
do not leak into applications that only use the synchronous client.

## Proposed API Shape

Introduce a parallel suspend driver:

```kotlin
interface SuspendDriver {
    suspend fun insert(table: String, values: Map<String, Any?>): Map<String, Any?>
    suspend fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>?
    suspend fun byId(table: String, id: Any): Map<String, Any?>?
    suspend fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>

    suspend fun <T> withTransaction(block: suspend (SuspendDriver) -> T): T
    val inTransaction: Boolean
}
```

Generate a suspend client alongside or instead of the synchronous client:

```kotlin
val client = SuspendEntClient(PostgresR2dbcDriver(connectionFactory))

val user = client.users.byIdOrNull(id)

client.withTransaction { tx ->
    tx.posts.create {
        title = "Hello"
    }.save()
}
```

All database-touching terminals become `suspend`. Pure builder methods remain
regular methods.

## Codegen Strategy

There are two reasonable generation modes:

1. Generate synchronous APIs only.
2. Generate synchronous and suspend APIs side by side.

Side-by-side generation is more convenient but increases generated surface
area. A Gradle/codegen option can make this explicit:

```kotlin
entkt {
    generateSuspendClient.set(true)
}
```

Generated type names should avoid overload ambiguity. For example:

```kotlin
EntClient
SuspendEntClient
UserRepo
SuspendUserRepo
```

## Loader And Interceptor Behavior

Request-scoped loaders need a suspend equivalent:

```kotlin
suspend fun <T> withEntktRequest(block: suspend RequestScope.() -> T): T
```

Read-path interceptors can stay structurally similar, but hooks that perform
reads need suspend-capable variants:

```kotlin
interface SuspendReadInterceptor<E : Any> {
    suspend fun beforeQuery(shape: QueryShape<E>): QueryShape<E>
}
```

The sync and suspend tracks should not silently adapt to each other by blocking
threads.

## Transactions And Cancellation

Suspend transactions should respect coroutine cancellation:

- cancellation rolls back the transaction
- rollback failures are surfaced or suppressed consistently
- the transaction driver is confined to the transaction block
- nested behavior follows the transaction options RFC if implemented

See [Transaction Options And Savepoints](../mutation/transaction-options-savepoints.md).

## Driver Capabilities

The R2DBC driver may not match JDBC feature-for-feature at first. Capability
metadata should document differences, especially around:

- generated keys
- batch insert behavior
- savepoints
- row locking
- query explain support
- streaming result sets

See [Driver Capability Matrix](driver-capability-matrix.md).

## Test Requirements

Before implementation, add tests for:

- generated suspend terminals are `suspend`
- builder methods remain non-suspend
- suspend transactions commit and roll back correctly
- coroutine cancellation rolls back active transactions
- suspend request loaders batch concurrent loads
- sync clients do not depend on coroutine/R2DBC modules
- R2DBC driver capability metadata matches implemented behavior
