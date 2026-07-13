# RFC: Privacy-Aware Visible Pagination

## Status

Possible future feature. This is not implemented.

## Summary

Add an explicit API for returning up to `N` visible rows when LOAD privacy is
evaluated after storage reads.

The current `visibleAll()` API filters the storage window selected by the
driver. If a query asks for `limit(10)`, EntKT scans at most ten storage rows
and returns the subset that passes LOAD privacy. That is clear once documented,
but it can surprise callers who expect "ten visible rows".

This RFC describes the problem and the main solution shapes:

- keep the current raw-window semantics and rename or document them more
  explicitly
- push privacy predicates into SQL when rules can be represented as predicates
- add an explicit bounded visible-scan pagination API for arbitrary Kotlin
  privacy rules
- use a hybrid model where predicate pushdown narrows the database query and
  LOAD privacy remains the final authority

## Motivation

EntKT has two useful but different read semantics today:

```kotlin
query.allOrThrow()
```

Strictly loads the selected storage rows and rejects the whole operation if any
selected entity fails LOAD privacy.

```kotlin
query.firstVisibleOrNull()
query.visibleAll()
```

Scans storage rows and filters out entities that fail LOAD privacy.

The hard case is paged lists. Consider:

```kotlin
val rows = client.conversationAssets.query {
    where(ConversationAsset.conversationId.eq(conversationId))
    orderBy(ConversationAsset.createdAt.desc())
    limit(10)
}.visibleAll()
```

Today, `limit(10)` means "scan at most ten matching storage rows, then return
the visible subset." If five of those rows fail LOAD privacy, the caller gets
five rows even if more visible rows exist just beyond the storage window.

That behavior is defensible as a bounded scan, but the API name does not make
the distinction obvious. It also makes offset pagination surprising because
`offset` skips storage rows, not visible rows.

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
separate visible-result limit. That creates least-surprise pressure around
`visibleAll()`.

## Non-Goals

- Do not replace arbitrary Kotlin LOAD privacy with SQL-only rules.
- Do not guarantee exactly `N` visible rows for unbounded data sets.
- Do not make privacy-denied rows observable through counts, offsets, or raw
  scanned totals.
- Do not solve request-scoped batching or N+1 behavior in this RFC. That is a
  related but separate query performance problem.
- Do not remove strict read APIs like `allOrThrow()`.

## Option A: Keep Current Semantics, Rename Or Clarify

Keep `visibleAll()` as a raw-window filter, or rename it to a name that makes
the storage-window behavior explicit:

```kotlin
query.visibleWindow()
query.visibleStorageWindow()
```

Semantics:

- driver applies `where`, `orderBy`, `limit`, and `offset`
- EntKT materializes the selected rows
- LOAD privacy filters denied rows
- the result may contain fewer rows than `limit`

Pros:

- smallest implementation
- keeps performance bounded by the existing query shape
- easy to document and test
- no hidden loops over large data sets

Cons:

- does not satisfy "give me up to ten visible rows"
- offset remains storage-offset, not visible-offset
- callers may still choose the wrong API unless naming is very explicit

This option is mainly a terminology and documentation fix. It does not solve
visible pagination.

## Option B: Predicate Pushdown

Add privacy-adjacent rules that can produce SQL predicates:

```kotlin
privacy {
    load(UserPrivacy.visibleWhere { User.orgId.eq(viewer.orgId) })
}
```

or model this as query interceptors/scopes:

```kotlin
interceptors {
    load { addPredicate(User.orgId.eq(viewer.orgId)) }
}
```

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

## Option C: Explicit Visible Scan Pagination

Add a terminal that says exactly what it does: scan storage rows in stable
order until it collects up to `N` visible rows, reaches storage exhaustion, or
hits a configured scan budget.

Example:

```kotlin
val page = client.conversationAssets.query {
    where(ConversationAsset.conversationId.eq(conversationId))
    orderBy(ConversationAsset.createdAt.desc())
}.visibleScanPage(
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

## Option D: Hybrid Model

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

Adopt Option D in phases.

Phase 1: clarify the current API.

- document that `visibleAll()` filters a storage window
- consider renaming or adding an alias such as `visibleWindow()`
- reject or warn on confusing combinations if needed

Phase 2: add explicit visible scanning.

```kotlin
query.visibleScanPage(
    visibleLimit = 20,
    after = cursor,
    scanLimit = 1_000,
)
```

Rules:

- require stable ordering, or append primary key as a generated tie-breaker
- reject caller-authored `limit` and `offset` on `visibleScanPage()`
- do not expose denied-row counts by default
- treat `ScanLimitReached` as a normal boundary, not an exception

Phase 3: add predicate pushdown for predicate-shaped visibility rules.

- keep LOAD privacy as final authority
- make pushed rules explicit in naming and docs
- ensure generated explain/diagnostic tools can show which predicates were
  applied

## Open Questions

- Should the current `visibleAll()` be renamed, deprecated, or kept with
  stronger docs?
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

- `visibleAll()` continues to filter only the selected storage window
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
