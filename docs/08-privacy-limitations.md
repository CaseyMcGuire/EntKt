# Privacy Limitations

Privacy V1 intentionally keeps enforcement synchronous and row-by-row.
The following limitations are part of the current contract.

## Aggregate Reads

`query.rawCount()` uses a driver aggregate fast path and does not
evaluate LOAD privacy. This means it can reveal how many rows match a
predicate, even if those rows would cause `query.all()` to throw
`PrivacyDeniedException`.

Use `query.visibleCount()` when you need a privacy-aware count. It
materializes matching rows, evaluates LOAD privacy on each, and returns
the count of allowed entities. Because it loads all rows into memory,
it is slower than `rawCount()` for large result sets.

`query.exists()` materializes one row and evaluates LOAD privacy on it,
so it is subject to the same strict read contract as `firstOrNull()`.

## Strict Read Model

`query.all()` throws `PrivacyDeniedException` if any matching entity is
denied by LOAD privacy. Eager-loaded edges throw in the same way —
if any eagerly loaded related entity is denied, the entire query fails.

`query.firstOrNull()` throws `PrivacyDeniedException` if the fetched
row is denied. It returns `null` only when no matching row exists.

Because privacy is evaluated after the driver applies `limit` and
`offset`, a query like `limit(10).all()` evaluates privacy on at most
ten rows. If any of those rows are denied, the query throws rather than
returning a partial result. Callers should ensure their predicates
narrow results to entities the viewer is allowed to see, or handle
`PrivacyDeniedException` at the call site.

## Bulk Operations

Generated `createMany()` and `deleteMany()` are convenience methods that
delegate through the per-entity create and delete paths so hooks and
privacy rules run for each item.

Because of that delegation, the privacy context provider may be invoked
once per item rather than once for the whole bulk call. Providers should
return a stable viewer for the duration of a request or logical
operation.

