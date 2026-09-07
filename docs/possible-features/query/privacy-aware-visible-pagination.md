# RFC: Privacy-Aware Visible Pagination

## Status

Possible future feature. This is not implemented.

## Summary

Add an explicit API for returning up to `N` visible rows when LOAD privacy is
evaluated after storage reads.

Current `all(viewerContext)` reads are strict: any denied root entity fails the
operation. `limit` and `offset` select storage rows before LOAD privacy. There
is no root collection `visibleAll()` terminal. Singular results can explicitly
project root denial to absence with `visibleOrNull()`; eager edges can filter
only their selected window with `filterVisible()`.

This RFC retains two open capabilities: predicate-shaped query visibility and
an explicit bounded scan that collects visible rows under arbitrary Kotlin
LOAD rules. Neither is a rename of an existing collection terminal.

## Motivation

A paged list currently uses:

```kotlin
val rows = client.conversationAssets.query {
    where(ConversationAsset.conversationId.eq(conversationId))
    orderBy(ConversationAsset.createdAt.desc())
    limit(10)
}.all(viewerContext).getOrThrow()
```

If any of those ten selected storage rows is denied, the read fails. It does
not return five visible rows or continue scanning to fill the page. Applications
that need that behavior require an explicit new contract for filtering, cost,
and continuation. Offset still skips storage rows, not visible rows.

## Problem Statement

EntKT should make these concepts explicit:

- **storage limit**: maximum rows fetched from the driver before privacy
  filtering
- **visible limit**: desired number of rows returned after privacy filtering
- **scan budget**: maximum storage rows EntKT is allowed to inspect while
  trying to collect visible rows
- **strict read**: fail if any selected row is denied
- **visible read**: omit denied rows without revealing their values

The current API exposes storage `limit` and `offset`, but does not provide a
separate visible-result limit or root collection filtering terminal.

## Non-Goals

- Do not replace arbitrary Kotlin LOAD privacy with SQL-only rules.
- Do not guarantee exactly `N` visible rows for unbounded data sets.
- Do not make privacy-denied rows observable through counts, offsets, or raw
  scanned totals.
- Do not solve request-scoped batching or N+1 behavior in this RFC. That is a
  related but separate query performance problem.
- Do not remove strict reads such as `all(viewerContext)`.

## Option A: Predicate Pushdown

Add typed query-visibility predicates before storage bounds while keeping LOAD
privacy as the final authority. The API and relationship coverage belong to
[Query-Time Visibility Predicates](../privacy-validation/query-time-visibility-predicates.md).

Semantics:

- generated queries add the visibility predicate before ordering, limiting,
  counting, and pagination
- the database returns only rows that satisfy the pushed predicate
- arbitrary LOAD privacy still runs after materialization as a final safety net

Pros:

- correct SQL pagination for rules that can be expressed as predicates
- efficient for common ownership, tenant, soft-delete, and status filters
- aligns with how many frameworks handle query visibility
- counts and existence checks can be made privacy-aware for pushed rules

Cons:

- cannot express all Kotlin privacy logic
- risks creating two privacy systems if naming and docs are not careful
- requires access to viewer claims in predicate-building code
- can be surprising if callers assume every privacy rule was pushed down

This option is powerful, but it should not replace LOAD privacy. It should be
an optimization and correctness tool for predicate-shaped visibility rules.

## Option B: Explicit Visible Scan Pagination

Add a terminal that says exactly what it does: scan storage rows in stable
order until it collects up to `N` visible rows, reaches storage exhaustion, or
hits a configured scan budget.

Example:

```kotlin
val page = client.conversationAssets.query {
    where(ConversationAsset.conversationId.eq(conversationId))
    orderBy(ConversationAsset.createdAt.desc())
}.visibleScanPage(
    viewerContext = viewerContext,
    visibleLimit = 10,
    after = cursor,
    scanLimit = 500,
)
```

Possible return shape:

```kotlin
data class VisibleScanPage<T>(
    val rows: List<T>,
    val nextCursor: VisibleScanCursor?,
    val boundary: VisibleScanBoundary,
)

enum class VisibleScanBoundary {
    VisibleLimitReached,
    StorageExhausted,
    ScanLimitReached,
}
```

Semantics:

- `visibleLimit` is the maximum visible rows returned
- `scanLimit` is the maximum storage rows scanned while filling the page
- `after` is a stable storage cursor, not a visible offset
- denied rows are skipped without exposing their values
- if `ScanLimitReached`, the result may contain fewer than `visibleLimit` rows
  and still include a continuation cursor

Pros:

- works with arbitrary Kotlin LOAD privacy
- makes the performance budget explicit
- avoids pretending that offset pagination can be visible-row based after
  post-load filtering
- gives API callers a clear partial-page state

Cons:

- may require multiple storage reads per page
- cannot guarantee full pages when denied rows are dense
- cursor semantics need careful design
- exposing scan statistics can create side channels if done carelessly

This option best matches the existing privacy model because it preserves
arbitrary LOAD privacy while making the cost and partial-page behavior visible.

## Option C: Hybrid Model

Support both predicate pushdown and explicit visible scanning.

Recommended mental model:

```text
query predicates + pushed visibility -> narrow the storage set
LOAD privacy                         -> final per-entity authority
visible scan                         -> collect visible rows with a budget
```

Example:

```kotlin
val page = client.assets.query {
    where(Asset.workspaceId.eq(workspaceId))
    orderBy(Asset.createdAt.desc())
}.visibleScanPage(
    viewerContext = viewerContext,
    visibleLimit = 20,
    scanLimit = 1_000,
)
```

If the application also defines a pushed workspace or ownership predicate, the
scan sees fewer denied rows and pages fill more efficiently. If a rule cannot
be pushed, LOAD privacy still filters it correctly.

Pros:

- efficient for common cases
- correct for arbitrary privacy rules
- avoids limiting the privacy API to SQL
- gives callers explicit control over scan cost

Cons:

- more API surface
- needs clear docs explaining which layer provides which guarantee
- implementation touches query codegen, privacy context access, cursors, and
  result variants

## Reference Implementation Notes

Other ORM and entity systems tend to split this problem rather than solve it
with one universal API:

- SQL-first systems commonly use scopes, querysets, or predicates before
  `LIMIT` and `OFFSET`.
- Ent-style privacy systems often distinguish query-time filtering from
  load-time authorization.
- DataLoader-style request batching helps reduce duplicate loads and N+1
  behavior, but it does not by itself make "return N visible rows" correct.

EntKT should preserve Kotlin-first arbitrary privacy while offering explicit
query-time tools for cases that need pagination-correct filtering.

## Proposed Direction

Explore the hybrid model without changing strict entity reads. Design the
bounded scan's cursor and result contract together with query-time visibility.
The proposed terminal should take an explicit `ViewerContext` and return
`ReadResult<VisibleScanPage<T>>`; operational failures remain failed reads,
while reaching a scan budget is a successful partial page.

Rules:

- require stable ordering, or append primary key as a generated tie-breaker
- reject caller-authored `limit` and `offset` on `visibleScanPage()`
- do not expose denied-row counts by default
- treat `ScanLimitReached` as a normal boundary, not an exception

When predicate-shaped visibility is added:

- keep LOAD privacy as final authority
- make pushed rules explicit in naming and docs
- ensure generated explain/diagnostic tools can show which predicates were
  applied

## Open Questions

- Should `visibleScanPage()` live directly on queries, or under a namespace
  that emphasizes privacy-aware scanning?
- What should the default `scanLimit` be, and should it be required?
- Should scan boundaries use a sealed class with extra diagnostic data instead
  of an enum?
- Should a page include `hasMoreStorage`, or is `nextCursor != null` enough?
- Can scan diagnostics be exposed safely without leaking denied-row density?
- How should this interact with future cursor pagination APIs?

## Test Requirements

Before implementation, add tests for:

- existing strict reads and eager `filterVisible()` semantics are unchanged
- `visibleScanPage(visibleLimit = N)` returns up to `N` allowed rows after
  skipping denied rows
- `visibleScanPage()` returns `ScanLimitReached` with a continuation cursor
  when the scan budget is exhausted
- `visibleScanPage()` returns `StorageExhausted` when no more matching storage
  rows exist
- stable ordering includes a primary key tie-breaker
- caller-authored `limit` and `offset` are rejected for `visibleScanPage()`
- denied rows do not leak through counts or diagnostics
- pushed predicates run before ordering, limits, counts, and pagination where
  that feature is enabled
