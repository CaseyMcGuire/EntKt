# RFC: Query `forUpdate()` Row Locking

## Status

Possible future feature. This is not implemented.

## Summary

Add query-level row locking for transaction-scoped reads:

```kotlin
val layout = requireNotNull(tx.assetPageLayouts.query {
    where(AssetPageLayout.assetId.eq(assetId))
    where(AssetPageLayout.page.eq(page))
    forUpdate()
}.firstOrNull(viewerContext).getOrThrow())
```

On Postgres this lowers to `SELECT ... FOR UPDATE`, locking the selected root
rows until the surrounding transaction commits or rolls back.

This is a lock-only read. It must run normal read interceptors and LOAD privacy,
but it must not run update privacy, update validation, mutation hooks, or issue
an `UPDATE`.

## Motivation

Some workflows need a stable row to act as a transaction-scoped mutex even when
that row is not being changed.

Example: page highlights. Each page layout has one stable row, so highlight
writers can serialize per-page edits by locking the layout row:

```kotlin
entClient.withTransaction { tx ->
    val layout = requireNotNull(tx.assetPageLayouts.query {
        where(AssetPageLayout.assetId.eq(assetId))
        where(AssetPageLayout.page.eq(page))
        forUpdate()
    }.firstOrNull(viewerContext).getOrThrow())

    val highlights = tx.highlights.indexes
        .assetId(assetId)
        .page(page)
        .query()
        .all(viewerContext).getOrThrow()

    // Merge, clip, create, and update highlights while the page lock is held.
}
```

Without the layout-row lock, two transactions can read the same initial
highlights and independently calculate incompatible changes. Locking only
existing highlight rows is not enough:

- a page may have no highlights yet, leaving nothing to lock
- locks on existing rows do not protect future inserted rows
- the operation needs one stable lock target representing the whole page

EntKT already has a low-level `DatabaseDriver.readRowForUpdate(table, id)` primitive for
pessimistic updates, but generated repositories expose it only through update
saves. Using a fake update to acquire a lock has the wrong semantics:

- it executes an unnecessary SQL `UPDATE`
- it runs update privacy and validation
- it may fire mutation hooks or audit behavior
- it requires permission to update an otherwise immutable row
- it creates write churn
- it falsely communicates that the row is changing

The API should make lock-only reads explicit.

## Non-Goals

- Do not add a dedicated by-id lock helper in V1.
- Do not make generated updates implicit locking reads.
- Do not run update privacy, update validation, mutation hooks, or write hooks.
- Do not lock eager-loaded edge rows in V1.
- Do not support locking aggregate, count, or existence terminals.
- Do not support visible-filtering terminals in V1.
- Do not add database-specific locking clauses beyond `FOR UPDATE` in V1.

## Proposed API

Generated query classes get a fluent method:

```kotlin
fun forUpdate(): AssetPageLayoutQuery
```

Usage:

```kotlin
val layout = requireNotNull(tx.assetPageLayouts.query {
    where(AssetPageLayout.assetId.eq(assetId))
    where(AssetPageLayout.page.eq(page))
    forUpdate()
}.firstOrNull(viewerContext).getOrThrow())
```

By-id locking remains query-shaped:

```kotlin
val layout = requireNotNull(tx.assetPageLayouts.query {
    where(AssetPageLayout.id.eq(layoutId))
    forUpdate()
}.firstOrNull(viewerContext).getOrThrow())
```

This keeps one API surface for locking reads and avoids a long generated method
name for a special case.

## Semantics

`forUpdate()` applies to the root rows returned by the query.

For a supported strict row terminal:

```kotlin
query {
    where(...)
    orderBy(...)
    limit(...)
    forUpdate()
}.all(viewerContext).getOrThrow()
```

EntKT should:

1. require a transaction-scoped client
2. require a driver with query-level `FOR UPDATE` support
3. run normal read interceptors before execution
4. execute the root query with the lock mode attached
5. hydrate returned rows
6. evaluate LOAD privacy
7. return entities, holding the database locks until transaction end

The lock is taken by the database before EntKT can run post-load privacy. That
means a strict read that later fails LOAD privacy may have locked a row before
returning a failed read. This is acceptable for strict reads because they selected
that row. Callers should rely on query predicates and read interceptors to narrow
the locked set before execution.

## Supported Terminals

The current row terminals are:

```kotlin
all(viewerContext)          // ReadResult<List<Entity>>
firstOrNull(viewerContext)  // ReadResult<Entity?>
```

Both would retain strict LOAD privacy and canonical result handling when
locking is requested. `getOrThrow()` projects the result after execution; a
required lock target also needs an explicit absence check such as
`requireNotNull(result.getOrThrow())`.

Generated count, aggregate, and root visible-filtering terminals no longer
exist. If future non-row or visible-scan terminals are added, their lock
semantics must be designed explicitly rather than inherited by default.
Collection calculations after a row read do not change which rows were locked.

Likewise, `visibleOrNull()` is a result projection after the read. It cannot
undo a lock acquired before LOAD denial or be rejected by a terminal before
execution. Lock lifetime remains the surrounding transaction lifetime even
when the caller projects the result to absence.

Preflight belongs in the shared runtime query path; generated code supplies
only the typed fluent method and lock metadata.

## Privacy And Interceptors

`forUpdate()` is a read feature, not a mutation feature.

It should run the same read pipeline as the corresponding non-locking terminal:

- caller predicates
- structural predicates from edge traversal
- read interceptors
- driver query
- entity hydration
- LOAD privacy
- eager edge loading, if requested

It should not run:

- update privacy
- update validation
- create/delete validation
- mutation hooks
- write hooks

Read interceptors should be able to add predicates before the lock is taken.
That matters for tenant filters, soft-delete filters, ownership scopes, and
edge-derived visibility predicates.

V1 does not need an interceptor API for adding or removing `forUpdate()`.
Callers opt into locking explicitly.

## Edge Traversal And Eager Loading

For traversals:

```kotlin
tx.users.query {
    where(User.id.eq(userId))
}.queryPosts {
    where(Post.status.eq(Status.DRAFT))
    forUpdate()
}.all(viewerContext).getOrThrow()
```

`forUpdate()` locks the target query rows (`posts`), not the source rows
(`users`). The source query still contributes a bridge predicate through the
normal traversal machinery.

For eager loading:

```kotlin
tx.posts.query {
    forUpdate()
    loadAuthor()
}.all(viewerContext).getOrThrow()
```

V1 locks only the root `posts` rows. Eager-loaded `author` rows are ordinary
reads. Locking eager edges can be a later feature if a concrete use case needs
it.

## Runtime And Driver Shape

The existing low-level `readRowForUpdate(table, id)` driver method is
id-specific and cannot express arbitrary query predicates, ordering, limits, or
read-interceptor predicates. Query-level locking needs a new query lock mode in
the normal query path.

Possible runtime shape:

```kotlin
enum class QueryLockMode {
    None,
    ForUpdate,
}

data class StorageQuerySpec<E : Any>(
    val predicates: List<Predicate<E>>,
    val orderBy: List<OrderField<E>>,
    val limit: Int?,
    val offset: Int?,
    val lockMode: QueryLockMode,
    // existing fields...
)
```

Driver capability:

```kotlin
val supportsQueryForUpdate: Boolean
```

Driver query execution should receive the lock mode explicitly. The exact
signature can be decided during implementation, but the lock must be part of
the normal query path so read interceptors and structural predicates are
included in the locking SQL.

Postgres rendering should append `FOR UPDATE` to the root select when
`lockMode == QueryLockMode.ForUpdate`.

## Transaction Requirements

`forUpdate()` must require an active transaction:

```kotlin
entClient.withTransaction { tx ->
    tx.assetPageLayouts.query {
        where(...)
        forUpdate()
    }.firstOrNull(viewerContext).getOrThrow()
}
```

Calling a `forUpdate()` terminal outside a transaction should fail before the
driver query runs. The existing `TransactionRequiredException` shape is the
likely fit.

If the driver does not support query-level `FOR UPDATE`, the shared runtime query path
should fail before driver execution with `UnsupportedDriverCapabilityException`.

## Diagnostics

A future query diagnostics surface should show the root lock mode and leave
ordinary eager subplans unlocked. The previous generated `explain*` terminal
family is no longer exposed; this RFC does not depend on restoring it. See
[Query Observability Diagnostics](query-observability-diagnostics.md).

## Open Questions

- Which lock-shape errors are deterministic programming exceptions, and which
  belong in `ReadResult.Failed`, consistently with current query preflight?
- Should `forUpdate()` be rejected immediately outside a transaction, or only
  when a terminal executes?
- Should lock mode be visible to read interceptors in `QueryContext`, even if
  interceptors cannot mutate it in V1?
- Should V1 support `FOR NO KEY UPDATE`, `FOR SHARE`, or `SKIP LOCKED`, or keep
  only `FOR UPDATE` until there are concrete use cases?

## Test Requirements

Before implementation, add tests for:

- generated queries expose `forUpdate()` and preserve fluent chaining
- `forUpdate()` outside a transaction fails before driver execution
- unsupported drivers fail before driver execution
- Postgres renders `SELECT ... FOR UPDATE`
- Postgres locks are held until transaction commit/rollback
- read interceptors add predicates before the locking query executes
- LOAD privacy is evaluated after hydration
- update privacy, validation, and mutation hooks do not run
- edge traversal locks target rows, not source rows
- eager-loaded edge rows are not locked in V1
- result projections preserve the executed read's lock lifetime
- absence can be distinguished from a successfully locked row
