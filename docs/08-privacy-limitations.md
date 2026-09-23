# Privacy Limitations

Privacy enforcement is synchronous and callback-driven. Explicit batch rules
can combine application reads, but EntKt does not rewrite arbitrary scalar
callbacks or infer a set-based query. The following limitations are part of the
current contract.

## Counts and Aggregate Calculations

Generated queries do not expose count, existence, or aggregate terminals.
Callers read entities with `all(viewerContext)` and calculate over the returned
collection in Kotlin; an existence check can use
`firstOrNull(viewerContext) != null`. These reads materialize entities and run
LOAD privacy under the supplied context.

An intentional storage-wide calculation therefore requires an explicit
`ViewerContext.privacyBypass_DANGEROUS(reason)`. This is available inside
privacy rules as well, because rules are trusted authorization code, but it
must be visible at the entity terminal that performs the read. Read
interceptors still run. Because the generated API materializes the matching
entities, applications should use an application-owned storage query with a
documented authorization boundary when a large aggregate cannot reasonably be
calculated in memory.

## Collection Outcomes And Strict Projection

`query.all(viewerContext)` returns `ReadCollectionResult.Completed` with one
success or root-denial outcome per selected row. `getOrThrow()` throws on any
denial, aggregating keyed diagnostics in query order. `deniedAsNull()` instead
turns denied root entries into successful nulls, preserving order and length.
Those null slots disclose hidden rows' existence and positions; applications
must deliberately choose whether that representation is appropriate.
Whole-query failures remain failures, with no partial entries exposed.
Eager-loaded edges fail with a `SelectedEdgePath(steps)` origin —
if any eagerly loaded related entity is denied, the entire query fails
(unless that edge opts into `filterVisible()`).

`query.firstOrNull(viewerContext)` returns `Failed(EntPrivacyDeniedException(Root, ...))`
if the fetched row is denied. It returns `Success(null)` only when no
matching row exists. For singular reads, `.visibleOrNull()` explicitly
maps that root denial to absence.

Privacy is evaluated after `limit` and `offset` select the result window, so
`limit(10).all(viewerContext)` evaluates privacy on at most ten rows; choosing a result
projection never turns a bounded query into a scan. If any of those rows are
denied, strict projection throws; null projection keeps their slots without
replacement reads. Selected graphs are loaded for authorized roots even if
other roots are denied. Strict projection afterward cannot undo those reads.

## Predicate-Based Inference

LOAD privacy is evaluated only for entities returned by a read.
Related rows used to decide whether a result matches are not themselves
LOAD-checked:

- `Edge.has { ... }` / `Edge.exists()` can match a related row the viewer
  could not load directly.
- `queryX()` applies the source query when selecting target rows —
  including its `orderBy` / `limit` / `offset` — but it does not return
  or LOAD-check those source entities. A source ordered by a hidden
  attribute and bounded (`orderBy(...); limit(1)`) can therefore leak
  *rank* information about hidden rows, not just existence.

A query can therefore be *filtered* by attributes of rows the viewer
could not load, and its LOAD-checked results reveal that match. This
applies uniformly to application queries and to privacy-rule reads —
a rule that keys a decision on a hidden related row's attributes
through `has { }` is influenced by data its viewer cannot see. When a
decision must not use hidden related data, load the related row explicitly
(`findById(viewerContext, id)` / `firstOrNull(viewerContext)`) so it passes its own LOAD check.

## Bulk Operations

Generated `createMany(viewerContext, ...)` and
`deleteMany(viewerContext, ...)` evaluate privacy rule-major over the complete
candidate list and retain the supplied viewer context for the logical
operation. A write-side CREATE or DELETE denial aborts the target write before
persistence; it is not treated as filtering. Returned LOAD privacy for
`createMany(viewerContext, ...)` runs after insertion, so its failure carries the actual write
state (`Committed` for a confirmed EntKt-owned commit or `TransactionPending`
inside a caller transaction). Scalar rules still run once per item through
their batch adapter, so a scalar rule that queries per item retains N+1
behavior. Register an explicit batch rule when one set-based query is required.
