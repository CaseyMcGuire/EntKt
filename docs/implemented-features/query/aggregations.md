# RFC: Aggregations

## Status

Implemented (V1). The generated `<Entity>Query` exposes the single-metric raw
aggregate terminals (`rawMin`/`rawMax`/`rawSum`/`rawAvg` + the grouped `raw…By`
forms, each with an `…OrError` twin) over Postgres. As-built notes vs. this spec:

- The compile-time type gating is carried by column-handle markers on the
  generated companion columns: `IntegralColumn`/`FloatingColumn` (under
  `NumericColumn`) gate `sum`/`avg`; `GroupableColumn`/`NullableGroupableColumn`
  gate group keys (bool/uuid use a non-comparable `GroupableScalarColumn`); bytes
  and pgvector are non-groupable. No aggregate **result** types are generated.
- Enum group keys decode through `EnumColumn`'s generated `fromName` lambda — the
  driver returns the stored `String`, the terminal maps it back to the enum.
- The low-level `Driver.aggregate` validates `column`/`groupBy` against the
  registered schema (field-named error) and rejects a stray `COUNT` column before
  rendering SQL.

See the user guide at [Queries → Aggregations](../../04-queries.md#aggregations).

This RFC **extends the existing read-terminal spine** (`rawCount` / `visibleCount`
/ `rawExists` / `visibleExists`) rather than introducing a parallel query system.
Today the only aggregate is `rawCount()` over `Driver.count(table, predicates)`;
V1 generalizes that one shape to the other four SQL aggregates, reusing the same
interceptor pipeline, the same `ReadOperation`-driven privacy contract, and the
same `…OrError` result variants.

## Summary

Add typed **raw** aggregate terminals for `count`, `min`, `max`, `sum`, and `avg`,
computed in the database over a query's predicates. Each terminal computes a
**single** metric, optionally grouped by **one** column, and returns either a
plain typed value (ungrouped) or a list of typed buckets (grouped). V1 is
Postgres-only and, like `rawCount`, bypasses LOAD privacy — the `raw` prefix names
that contract.

## Motivation

Applications often need reporting queries:

- count posts by author
- find latest post date
- average age by account status
- sum order totals by day

Today users must drop to driver-specific SQL or build custom repository helpers.

Each need maps to exactly one V1 terminal:

| Need | V1 terminal |
| --- | --- |
| count posts by author | `posts.query { … }.rawCountBy(Post.authorId)` |
| find latest post date | `posts.query { … }.rawMax(Post.createdAt)` |
| average age by status | `users.query { … }.rawAvgBy(User.status, User.age)` |
| sum order totals by day | `orders.query { … }.rawSumBy(Order.day, Order.total)` \* |

\* "by day" here groups by a stored `day`/date column. Grouping by a *derived
expression* (`date_trunc('day', created_at)`) is deferred — see Non-Goals.

## V1 scope (decisions)

These resolve the questions the earlier draft left open:

1. **Result shape — generic typed values, no generated types** (see below).
2. **Privacy — raw only.** Terminals are `raw*`; they do not evaluate LOAD
   privacy. `visible*` / `checked*` are deferred.
3. **One metric per call.** No multi-metric `aggregate { }` block in V1; the
   single-metric terminals compose, and a block is a clean future extension.
4. **Group by zero or one plain column.** Multiple/expression group keys deferred.
5. **Postgres only.** Other drivers throw until they implement the contract.

## Proposed API

### Ungrouped terminals

```kotlin
val total:  Long     = client.posts.query { where(Post.published eq true) }.rawCount()
val latest: Instant? = client.posts.query { where(Post.published eq true) }.rawMax(Post.createdAt)
val oldest: Instant? = client.posts.query { }.rawMin(Post.createdAt)
val views:  Long?    = client.posts.query { }.rawSum(Post.viewCount)   // integral column → Long?
val avgLen: Double?  = client.posts.query { }.rawAvg(Post.length)      // → Double?
```

An ungrouped metric returns `null` when no row matches (the SQL aggregate of the
empty set), except `rawCount()`, which returns `0`.

### Grouped terminals (one group column)

```kotlin
val perAuthor: List<AggregateBucket<Long, Long>> =
    client.posts.query { where(Post.published eq true) }.rawCountBy(Post.authorId)

for (b in perAuthor) {
    val author: Long = b.key      // typed by Post.authorId : Column<Post, Long>
    val n:      Long = b.value
}

val avgAgeByStatus: List<AggregateBucket<Status, Double?>> =
    client.users.query { }.rawAvgBy(User.status, User.age)
```

`AggregateBucket` is a generic runtime type (in `entkt.runtime`), **not** generated:

```kotlin
data class AggregateBucket<K, V>(val key: K, val value: V)
```

There is one bucket per distinct key that has at least one matching row. Bucket
order is unspecified (post-sort in Kotlin if needed). `rawCountBy` values are `≥ 1`;
`min/max/sum/avg` bucket values are nullable (a bucket whose metric column is NULL
for all of its rows yields a `null` value).

### Result-shape decision (data class vs. row API)

A per-call generated data class — the earlier draft's
`PostAggregateRow(authorId, count, maxCreatedAt)` — is **not implementable**:
entkt's generator runs over the *schema* at build time and never sees call sites,
so it cannot know that a given call selected `count + max grouped by authorId`,
nor give the group key a precise type when any column could be the key.

V1 therefore returns **generic, strongly-typed runtime values** — plain scalars
and `AggregateBucket<K, V>` — with all typing carried by the `Column<E, T>` handle
you pass in. **No aggregate result types are generated.**

### `…OrError` variants

Every terminal has an `…OrError` twin returning `EntResult<…>`, with the exact
failure mapping of the shipped `rawCountOrError`:

- interceptor rejection → `EntResult.Err(QueryRejected)`
- any other driver exception → `EntResult.Err(classifyDriverError(…))`
- no `PrivacyDenied` arm — raw terminals never evaluate LOAD privacy

```kotlin
val r: EntResult<List<AggregateBucket<Long, Long>>> =
    client.posts.query { }.rawCountByOrError(Post.authorId)
```

## Type rules

`min`/`max` accept any **comparable** scalar column (`ComparableColumn<E,T>`):
string/text, int/long/float/double, time. `sum`/`avg` accept **numeric** columns
only. Both bounds are enforced **at compile time** by marker interfaces emitted on
the generated column handles:

- `NumericColumn<E,T>` gates `sum`/`avg`, split into `IntegralColumn` (INT, LONG)
  and `FloatingColumn` (FLOAT, DOUBLE) so overload resolution returns `Long?` vs
  `Double?`.

Users never name these markers — they pass `Order.total` and the right overload is
chosen. **Enum, bytes, JSON, and pgvector are excluded from `min`/`max` by type**:
`EnumColumn` and the base `Column` (bytes/pgvector) do not extend `ComparableColumn`,
and `JsonColumn` is not a `Column` at all. Excluding enums is deliberate — enum
ordering here is alphabetical on `.name`, which is rarely the intended min/max.

These markers (plus the `GroupableColumn` markers in **Grouping** and enum-decode
metadata on `EnumColumn`) are the column-handle layer V1 adds. No aggregate **result
types** are generated — that decision stands; the additions are markers/metadata on
the column handles that already exist.

| Function | Accepts | Returns (ungrouped) | Bucket value (grouped) | `null` / empty |
| --- | --- | --- | --- | --- |
| `count` | (no column) | `Long` | `Long` (`≥ 1` per bucket) | never — `0` when empty |
| `min` | `ComparableColumn<E,T>` | `T?` | `T?` | `null` when empty / all-NULL |
| `max` | `ComparableColumn<E,T>` | `T?` | `T?` | `null` when empty / all-NULL |
| `sum` | `IntegralColumn<E,*>` | `Long?` | `Long?` | `null` when empty / all-NULL |
| `sum` | `FloatingColumn<E,*>` | `Double?` | `Double?` | `null` when empty / all-NULL |
| `avg` | `NumericColumn<E,*>` | `Double?` | `Double?` | `null` when empty / all-NULL |

Notes:

- `sum` of an integral column returns `Long?` (Postgres widens `int4` → `int8`),
  **not** `Int?`, to avoid overflow on row-count-scale sums. A sum exceeding
  `Long` is out of V1 scope (it would need a `BigDecimal`/`DECIMAL` column type,
  which does not exist yet).
- `avg` is always `Double?`, including for integer inputs (`avg([1, 2]) == 1.5`).
- SQL aggregates skip NULL inputs; `min/max/sum/avg` over zero non-NULL rows is
  `null`. `count` counts rows (`COUNT(*)`), so it is `0`, never `null`.

A lighter alternative — a single `NumericColumn` marker with `sum` returning
`Double?` for all numeric columns — is rejected for V1 on least-surprise grounds
(summing integers should yield an integer), but is a viable fallback if the
integral/floating split is judged not worth the two extra markers.

## Grouping semantics

- `groupBy` is **zero or one** column, accepted as a `GroupableColumn<E,K>` — a
  marker emitted on the groupable kinds: **string/text, bool, int/long/float/double,
  time, UUID, enum**. **Bytes and pgvector are excluded** (the marker is required
  precisely because bool/uuid and bytes/pgvector all share the base `Column` type
  today and can't otherwise be told apart); JSON is already excluded (`JsonColumn`
  isn't a `Column`).
- No group column → one aggregate over all matched rows (ungrouped terminals).
- One group column → SQL `GROUP BY "col"`, one bucket per distinct key value.
- **Nullable keys are typed by overload, not type-param widening** — a nullable
  column carries its nullability on a marker, not in `K`, so each grouped terminal
  has two overloads:
  - `rawCountBy(key: GroupableColumn<E,K>): List<AggregateBucket<K, Long>>`
  - `rawCountBy(key: NullableGroupableColumn<E,K>): List<AggregateBucket<K?, Long>>`

  so `rawCountBy(Post.authorId)` → `AggregateBucket<Long, Long>` and
  `rawCountBy(Post.deletedAt)` → `AggregateBucket<Instant?, Long>`. A nullable group
  column folds its NULLs into the single `key == null` bucket.
- **Enum group keys decode via column metadata.** The driver returns an enum column
  as its stored `String` (the `Enum.valueOf` has always lived in entity `fromRow`,
  which the aggregate path bypasses), so the grouped terminal converts the key back
  to the enum using decode metadata carried on `EnumColumn`. This is what makes
  `rawAvgBy(User.status, User.age): List<AggregateBucket<Status, Double?>>` typed.
- Bucket order is unspecified (database default); callers post-sort.
- **Deferred:** multiple group columns, grouping by a derived expression
  (`date_trunc`, etc.), `HAVING`, `COUNT(DISTINCT …)`, and ordering/limiting
  buckets in SQL.

## Privacy behavior

V1 ships **raw** aggregates only. The `raw` prefix is the contract, identical to
`rawCount` / `rawExists`: the metric is computed in the database over the
(possibly interceptor-shaped) predicates, and **LOAD privacy is not evaluated** —
no rows are materialized, so per-row privacy cannot be applied. This is the
observability-sensitive surface the original draft flagged; naming it `raw*` makes
the bypass explicit at every call site.

Deferred privacy modes (future phases), mirroring the shipped `visibleCount`:

- **`visible*`** — materialize matching rows, evaluate LOAD privacy on each, and
  aggregate the survivors in memory. (`visibleSum` / `visibleAvg` would load every
  matching row; that cost is why they are deferred, not the default.)
- **`checked*`** — throw if any matched row is denied.

Interceptors still apply to raw aggregates exactly as they do to `rawCount`:
predicate-shaping interceptors (tenant scoping, the generated `soft-delete` filter)
run through the same pipeline, so soft-deleted rows are excluded from
`rawCount` / `rawSum` / buckets by default.

## Interceptor & `ReadOperation` alignment

- Add `RAW_AGGREGATE` to `entkt.runtime.ReadOperation`, beside `RAW_COUNT`.
- It maps to `EntOperation.QUERY` for rejection reporting (the existing rule is
  "everything except `BY_ID` → `QUERY`").
- `limitOpsApply(RAW_AGGREGATE) = false` — aggregates ignore `limit` / `offset`,
  so a `MaxLimitInterceptor` never silently caps an aggregate (the same reasoning
  that lists `RAW_COUNT` as a no-op there).
- Each terminal runs `val spec = runReadInterceptors(ReadOperation.RAW_AGGREGATE,
  EntOperation.QUERY)` and forwards `spec.predicates` to the driver. `orderBy`,
  `limit`, and `offset` are **not** forwarded (consistent with `rawCount`).
- `…OrError` twins reuse `rawCountOrError`'s try/catch:
  `EntQueryRejectedException → Err(e.queryRejected)`; any other `Exception →
  Err(classifyDriverError(driver, e, schema, EntOperation.QUERY))`.

## Driver contract

One new method on `entkt.runtime.Driver`, threading predicates exactly like
`count`:

```kotlin
fun aggregate(
    table: String,
    function: AggregateFunction,      // COUNT, SUM, AVG, MIN, MAX
    column: String?,                  // metric column; null only for COUNT(*)
    predicates: List<Predicate<*>>,   // AND-ed, same contract as count()
    groupBy: String? = null,          // single group column, or null
): List<AggregateResultRow>

enum class AggregateFunction { COUNT, SUM, AVG, MIN, MAX }

/** One result row. [key] is null when ungrouped; [value] is the metric (null per SQL NULL). */
data class AggregateResultRow(val key: Any?, val value: Any?)
```

- An ungrouped call returns exactly one `AggregateResultRow` (`key == null`).
- The default `Driver.aggregate` throws `UnsupportedDriverCapabilityException`
  ("driver _X_ does not support aggregate queries"); only `PostgresDriver`
  overrides it in V1, gated by `supportsAggregates(): Boolean = false` → `true` on
  Postgres (mirroring `supportsTypedJson`).
- Because the method takes raw `String?` identifiers, an implementation **must
  validate `column` and `groupBy` against the registered `EntitySchema` for
  `table` before rendering SQL**, and reject an unknown column with a clear,
  field-named entkt error — a bad identifier must never reach the database as a
  driver error. The generated terminals only ever pass real `Column<E,*>` names,
  so this is defense-in-depth for the low-level entry point (and the contract a
  future non-generated caller can rely on).

### Postgres lowering

Reuses the predicate/`WHERE` rendering that `count()` already uses:

```sql
-- ungrouped
SELECT <FN>(<col> | *) FROM "<table>" [WHERE <preds>];
-- grouped
SELECT "<group>", <FN>(<col> | *) FROM "<table>" [WHERE <preds>] GROUP BY "<group>";
```

- Before rendering, `PostgresDriver.aggregate` resolves the registered
  `EntitySchema` for `table` (the driver already caches it from `register`) and
  validates that `column`/`groupBy` (when non-null) are real columns on it — an
  unknown identifier fails with a field-named error (e.g.
  `"orders.bogus is not a column on orders"`) before any SQL is built. Validated
  identifiers are then quoted; predicate values are parameterized (`?`) — the same
  SQL-injection posture as `count`.
- Decode: `COUNT → Long`; `SUM(int/long) → Long`; `SUM(float/double) → Double`;
  `AVG → Double`; `MIN/MAX →` the metric column's decoded type for comparable
  scalars (`MIN(created_at) → Instant`; enums are not min/max-able). The group key
  decodes as the group column's type — **except enum keys**, which the driver
  returns as their stored `String`; the terminal maps them back to the enum via
  `EnumColumn` decode metadata (the driver never does enum `valueOf` — that lives in
  `fromRow`, which aggregates bypass).
- `explainAggregate` should mirror `explainCount` (it feeds the
  explain-interceptor surface). It may land in the same phase or just after; it is
  not required for the core terminals.

## Non-Goals (V1)

- Multi-metric selection in one pass (`count()` **and** `max()` in one row). The
  single-metric terminals compose; a multi-metric `aggregate { }` block is a clean
  future extension (it would return a typed row-accessor object, **not** generated
  classes — the same no-codegen constraint applies).
- `visible*` / `checked*` privacy-aware aggregates.
- `min`/`max`/`sum`/`avg` over enum columns — enums are **group keys only** in V1
  (their natural ordering is alphabetical-by-name, rarely the intended aggregate).
- Multiple group columns; expression / `date_trunc` group keys; `HAVING`;
  `COUNT(DISTINCT …)`; SQL-side bucket ordering or limiting.
- Aggregates across edges / joins.
- Non-Postgres drivers.
- A full SQL expression DSL; replacing the raw driver escape hatches.

## Test requirements

- **Schema / codegen:** numeric columns expose `NumericColumn` / `IntegralColumn` /
  `FloatingColumn`; groupable columns expose `GroupableColumn` /
  `NullableGroupableColumn`; `EnumColumn` carries enum-decode metadata. Compile-time
  negative fixtures (or codegen unit assertions): `min`/`max` reject
  enum/bytes/JSON/pgvector; `sum`/`avg` reject non-numeric; `groupBy` rejects
  bytes/pgvector/JSON.
- **Runtime / interceptor:** `RAW_AGGREGATE` maps to `QUERY`; `limitOpsApply` is
  `false`; a predicate-shaping interceptor (and the soft-delete filter) affects
  aggregate results; a rejecting interceptor yields `Err(QueryRejected)` from the
  `…OrError` twin.
- **Driver (Postgres, testcontainers):** `count/min/max/sum/avg` over supported
  types, ungrouped and grouped-by-one-column; **an enum group key comes back as
  its stored `String`** (the generated terminal does the enum decode — proven at
  the integration level); empty-set returns (`0` for count, `null` otherwise);
  NULL-input skipping; nullable group key → one `key == null` bucket; integral
  `sum` widens to `Long`; `avg` of integers is fractional; **an unknown metric or
  group column, a type-incompatible metric (`SUM(text)`, `MIN(enum)`), or a
  `COUNT` with a column throws a field-named error before any SQL runs**.
- **Unsupported driver:** a non-Postgres `Driver` throws from `aggregate`
  (`supportsAggregates() == false`).

## Future extensions

- Multi-metric block — `rawAggregate { count(); max(...); groupBy(...) }` — over a
  typed row-accessor result (still no generated result types).
- `visibleAggregate` / `checkedAggregate` privacy modes.
- Expression group keys (so "sum totals by day" needs no stored `day` column),
  multi-column grouping, `HAVING`, `COUNT(DISTINCT)`.
- Additional drivers once a second SQL driver exists.
