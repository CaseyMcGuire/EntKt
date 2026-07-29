# Privacy Limitations

Privacy V1 intentionally keeps enforcement synchronous and row-by-row.
The following limitations are part of the current contract.

## Aggregate Reads

`query.rawCount()` uses a driver aggregate fast path and does not
evaluate LOAD privacy. This means it can reveal how many rows match a
predicate, even if those rows would cause `query.allOrThrow()` to throw
`PrivacyDeniedException`.

Use `query.visibleCount()` when you need a privacy-aware count. It
materializes matching rows, evaluates LOAD privacy on each, and returns
the count of allowed entities. Because it loads all rows into memory,
it is slower than `rawCount()` for large result sets.

`query.exists()` materializes one row and evaluates LOAD privacy on it,
so it is subject to the same strict read contract as `firstOrNull()`.

## Strict Read Model

`query.allOrThrow()` throws `PrivacyDeniedException` if any matching entity is
denied by LOAD privacy. Eager-loaded edges throw in the same way —
if any eagerly loaded related entity is denied, the entire query fails.

`query.firstOrNull()` throws `PrivacyDeniedException` if the fetched
row is denied. It returns `null` only when no matching row exists.

Because privacy is evaluated after the driver applies `limit` and
`offset`, a query like `limit(10).allOrThrow()` evaluates privacy on at most
ten rows. If any of those rows are denied, the query throws rather than
returning a partial result. Callers should ensure their predicates
narrow results to entities the viewer is allowed to see, or handle
`PrivacyDeniedException` at the call site.

## Predicate-Based Inference

LOAD privacy is evaluated on materialized rows. Predicates that
reference *other* rows without materializing them do not evaluate
those rows' LOAD privacy:

- `Edge.has { ... }` / `Edge.exists()` compile to `EXISTS` subqueries
  against the related table (target-entity *interceptors* apply inside
  the subquery; LOAD privacy does not — see
  [Read-Path Interceptors → Edge-predicate existence semantics](implemented-features/query/read-path-interceptors.md)).
- `queryX()` traversals fold the source query's predicates into a
  structural bridge on the target; source rows are never loaded.

A query can therefore be *filtered* by attributes of rows the viewer
could not load, and its LOAD-checked results reveal that match. This
applies uniformly to application queries and to privacy-rule reads —
a rule that keys a decision on a hidden related row's attributes
through `has { }` is influenced by data its viewer cannot see. When a
decision must not be, materialize the related row explicitly
(`byIdOrNull` / `firstOrNull`) so it passes its own LOAD check.
Evaluating privacy through edge predicates is related ground to the
[Edge-Derived LOAD Privacy](possible-features/privacy-validation/edge-derived-load-privacy.md)
proposal.

## Bulk Operations

Generated `createMany()` and `deleteMany()` are convenience methods that
delegate through the per-entity create and delete paths so hooks and
privacy rules run for each item.

Because of that delegation, the privacy context provider may be invoked
once per item rather than once for the whole bulk call. Providers should
return a stable viewer for the duration of a request or logical
operation.

