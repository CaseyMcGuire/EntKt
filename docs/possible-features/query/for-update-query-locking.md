# RFC: Query `forUpdate()` Row Locking

## Status

Implemented, including the prerequisite [immutable query values](immutable-query-values.md)
refactor, full-client/read-only query type split, runtime/Postgres execution,
generated entry points, compile-time restrictions, and lock-lifetime tests.
See [Locking Reads](../../04-queries.md#locking-reads) for application usage.

## Summary

Add query-level row locking for transaction-scoped reads:

```kotlin
val layout = requireNotNull(tx.assetPageLayouts.query {
    where(AssetPageLayout.assetId.eq(assetId))
    where(AssetPageLayout.page.eq(page))
}.forUpdate().firstOrNull(viewerContext).getOrThrow())
```

On Postgres this lowers to `SELECT ... FOR UPDATE OF <root alias>`, holding
root-row locks until the surrounding transaction commits or rolls back. Native
database semantics apply; the locked set need not equal the returned entities
(see pagination below).

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
    }.forUpdate().firstOrNull(viewerContext).getOrThrow())

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
- Do not support explicit locking through privacy or validation rule clients.
- Do not support locking aggregate, count, or existence terminals.
- Do not support visible-filtering terminals in V1.
- Do not add database-specific locking clauses beyond `FOR UPDATE` in V1.
- Do not add automatic transactions, retries, isolation options, or savepoints.

## API

Completed full-client query values get a fluent method returning a shared
runtime wrapper:

```kotlin
fun forUpdate(): ForUpdateQuery<AssetPageLayout>
```

`ForUpdateQuery<Entity>` exposes only `all(viewerContext)` and
`firstOrNull(viewerContext)`. Finish configuration and traversal before calling
`forUpdate()`. Creating the wrapper performs no I/O and takes no locks; its
terminals execute the retained immutable query. Repeated terminals execute new
reads rather than consuming the wrapper or caching results.

Usage:

```kotlin
val layout = requireNotNull(tx.assetPageLayouts.query {
    where(AssetPageLayout.assetId.eq(assetId))
    where(AssetPageLayout.page.eq(page))
}.forUpdate().firstOrNull(viewerContext).getOrThrow())
```

By-id locking remains query-shaped:

```kotlin
val layout = requireNotNull(tx.assetPageLayouts.query {
    where(AssetPageLayout.id.eq(layoutId))
}.forUpdate().firstOrNull(viewerContext).getOrThrow())
```

This keeps one API surface for locking reads and avoids a long generated method
name for a special case.

## Semantics

`forUpdate()` applies to the final root table, not traversal sources or eager
targets.

For a supported strict row terminal:

```kotlin
query {
    where(...)
    orderBy(...)
    limit(...)
}.forUpdate().all(viewerContext).getOrThrow()
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

### Pagination And Native Database Behavior

`limit` and `offset` remain supported. EntKt will not reject offsets, fetch a
page of IDs first, or rewrite the query to conceal native locking behavior.
Postgres locks rows stepped past by `OFFSET`; concurrent predicate rechecks can
also leave locks on rows not returned. `OF <root alias>` limits row locking to
the root table, not to exactly the entities visible in the result.
See [Postgres locking clauses](https://www.postgresql.org/docs/16/sql-select.html#SQL-FOR-UPDATE-SHARE).

A cursor-style predicate on a stable ordered key can avoid stepping past rows
with `OFFSET`, but EntKt does not automatically choose or synthesize one.

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
}.forUpdate().all(viewerContext).getOrThrow()
```

`forUpdate()` locks the target query rows (`posts`), not the source rows
(`users`). The source query still contributes a bridge predicate through the
normal traversal machinery.

For eager loading:

```kotlin
tx.posts.query {
    loadAuthor()
}.forUpdate().all(viewerContext).getOrThrow()
```

V1 locks only the root `posts` rows. Eager-loaded `author` rows are ordinary
reads. Locking eager edges can be a later feature if a concrete use case needs
it.

Unsupported source and eager locking are absent from the configuration API:

```kotlin
tx.users.query { forUpdate() }                  // does not compile
tx.posts.query { loadAuthor { forUpdate() } }   // does not compile
tx.users.query().forUpdate().queryPosts()       // does not compile
```

Configuration scopes have no locking method, and the terminal-only wrapper has
no traversal method. Traverse first to lock the final target root:
`tx.users.query().queryPosts().forUpdate()`.

## Runtime And Driver Shape

The existing low-level `readRowForUpdate(table, id)` driver method is
id-specific and cannot express arbitrary query predicates, ordering, limits, or
read-interceptor predicates. Query-level locking needs a new query lock mode in
the normal query path.

Runtime shape (other existing storage fields omitted):

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

`DatabaseDriver.query` receives a final
`lockMode: QueryLockMode = QueryLockMode.None` parameter. The normal query path
includes read interceptors and structural predicates in the locking SQL;
custom driver overrides and decorators must forward the lock mode.

Postgres rendering should append `FOR UPDATE OF <root alias>` to the root select when
`lockMode == QueryLockMode.ForUpdate`.

## Transaction Requirements

`forUpdate()` must require an active transaction:

```kotlin
entClient.withTransaction { tx ->
    tx.assetPageLayouts.query {
        where(...)
    }.forUpdate().firstOrNull(viewerContext).getOrThrow()
}
```

Calling a `forUpdate()` terminal outside a transaction returns
`ReadResult.Failed(TransactionRequiredException)` before the driver query runs.

Rule-client locking is excluded by the generated types. Check client lifetime,
active transaction, and driver capability at terminal entry, before interceptors
or SQL. Creating a locking
query does not establish or prolong a transaction. No root-client query is
implicitly rebound to whichever transaction happens to be active.

If the driver does not support query-level `FOR UPDATE`, the shared runtime query path
should fail before driver execution with `UnsupportedDriverCapabilityException`.

## Diagnostics

A future query diagnostics surface should show the root lock mode and leave
ordinary eager subplans unlocked. The previous generated `explain*` terminal
family is no longer exposed; this RFC does not depend on restoring it. See
[Query Observability Diagnostics](query-observability-diagnostics.md).

## Approved Interceptor And Rule-Client Contracts

- **Interceptor metadata.** Expose read-only lock intent in `QueryContext`,
  while keeping `ReadOperation.ALL` / `FIRST`. Interceptors can inspect it but
  cannot add, remove, or retarget the lock. Traversal-source, edge-predicate,
  and eager-step contexts describe their own ordinary, unlocked reads.
- **Rule-client enforcement.** Locking through privacy and validation clients
  must not compile; a runtime-only rejection is insufficient. Those clients
  return separate `{Entity}ReadQuery` and `{Entity}ReadIndexes` types rather than
  the full-client `{Entity}Query` and `{Entity}Indexes`. Preserve this restriction
  through fluent refinement, index helpers, and traversal, including inside a
  transaction or under privacy bypass. Both families share `{Entity}QueryScope`
  and runtime execution. Add `forUpdate()` only to the full-client generated
  query, not the shared runtime query base.

## Approved Failure And Transaction Behavior

Preserve ordinary read semantics. Terminal
preflight and operational failures return `ReadResult.Failed`; cancellation and
fatal errors propagate. A LOAD denial does not by itself mark the transaction
rollback-only. The caller can use the existing `orRollback()` projection for a
required read. If a denial is handled and execution continues, acquired locks
remain until transaction end. An actual database statement failure can abort the
transaction regardless of how its result is handled; retain the existing
transaction-completion checks.

## Implementation Progress

1. **Implemented:** separate full-client and read-only query/index types, with
   shared scopes and runtime execution. Compile tests cover refinement, direct
   and many-to-many traversal, index stages, and privacy/validation rule reads.
2. **Implemented:** runtime locking wrapper, transaction/capability checks,
   read-only interceptor metadata, and Postgres root-row locking. Tests cover
   root/source/eager intent, preflight and read failures, SQL rendering, pinned
   transaction execution, and failed SQL preventing commit.
3. **Implemented:** thin generated `forUpdate()` entry points, negative compile
   tests for unsupported locking, concurrency/lock-lifetime tests, and usage
   documentation. PostgreSQL tests observe blocked writers resuming after both
   commit and rollback, root-only locking across direct/M2M traversal and eager
   loading, native offset behavior, and lock retention after LOAD denial.

## Test Requirements

Locking implementation must cover:

- completed queries expose a terminal-only `forUpdate()` wrapper
- configuration scopes cannot request source/eager locks, and locking wrappers
  cannot traverse
- `forUpdate()` outside a transaction fails before driver execution
- unsupported drivers fail before driver execution
- Postgres renders `SELECT ... FOR UPDATE OF <root alias>`
- Postgres locks are held until transaction commit/rollback
- read interceptors add predicates before the locking query executes
- LOAD privacy is evaluated after hydration
- update privacy, validation, and mutation hooks do not run
- edge traversal locks target rows, not source rows
- eager-loaded edge rows are not locked in V1
- result projections preserve the executed read's lock lifetime
- absence can be distinguished from a successfully locked row
- offsets retain native locking semantics without an extra page-selection query
- later immutable query branches cannot alter an existing locking wrapper
- rule-client locking does not compile, including after refinement, index
  helpers, and traversal
- denial, interceptor metadata, and transaction behavior match the decisions above
