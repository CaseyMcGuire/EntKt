# Privacy Limitations

Privacy V1 intentionally keeps enforcement synchronous and row-by-row.
The following limitations are part of the current contract.

## Aggregate Reads

`query.rawCount()` does not evaluate LOAD privacy. This means it can reveal
how many rows match a predicate, even if those rows would make
`query.all()` fail with `Failed(EntPrivacyDeniedException)`. The same
holds for `rawExists()` and the raw aggregates.

There is no privacy-aware count or existence terminal: the former
`visible*` scanning family was removed with the operation-result
algebra, and privacy-skipping scans are an explicit non-goal. A
viewer-visible count is a strict `all()` (which fails if any selected
row is denied) counted in Kotlin, over predicates that only match rows
the viewer may see.

## Strict Read Model

`query.all()` returns `Failed(EntPrivacyDeniedException(Root, ...))` if
any matching entity in the selected window is denied by LOAD privacy,
with one keyed `PrivacyDenial` per denied row — never a partial list.
Eager-loaded edges fail the same way with an `EagerEdge(path)` origin —
if any eagerly loaded related entity is denied, the entire query fails
(unless that edge opts into `filterVisible()`).

`query.firstOrNull()` returns `Failed(EntPrivacyDeniedException(Root, ...))`
if the fetched row is denied. It returns `Success(null)` only when no
matching row exists. For singular reads, `.visibleOrNull()` explicitly
maps that root denial to absence.

Privacy is evaluated after `limit` and `offset` select the result window, so
`limit(10).all()` evaluates privacy on at most ten rows; choosing a result
projection never turns a bounded query into a scan. If any of those rows are
denied, the read fails rather than returning a partial result. Callers should
narrow results to entities the viewer may see or handle the `Failed` state
explicitly.

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
(`findById` / `firstOrNull`) so it passes its own LOAD check.

## Bulk Operations

Generated `createMany()` and `deleteMany()` run hooks and privacy rules for
each item, inside one shared transaction — a denial anywhere aborts the
whole operation with no committed subset.

Because of that per-item delegation, the privacy context provider may be
invoked once per item rather than once for the whole bulk call. Providers
should return a stable viewer for the duration of a request or logical
operation.
