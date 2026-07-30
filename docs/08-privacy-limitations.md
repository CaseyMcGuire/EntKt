# Privacy Limitations

Privacy V1 intentionally keeps enforcement synchronous and row-by-row.
The following limitations are part of the current contract.

## Aggregate Reads

`query.rawCount()` does not evaluate LOAD privacy. This means it can reveal
how many rows match a predicate, even if those rows would cause
`query.allOrThrow()` to throw `PrivacyDeniedException`.

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

Privacy is evaluated after `limit` and `offset` select the result window, so
`limit(10).allOrThrow()` evaluates privacy on at most ten rows. If any of
those rows are denied, the query throws rather than returning a partial
result. Callers should narrow results to entities the viewer may see or handle
`PrivacyDeniedException`.

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
(`byIdOrNull` / `firstOrNull`) so it passes its own LOAD check.

## Bulk Operations

Generated `createMany()` and `deleteMany()` run hooks and privacy rules for
each item.

Because of that delegation, the privacy context provider may be invoked
once per item rather than once for the whole bulk call. Providers should
return a stable viewer for the duration of a request or logical
operation.
