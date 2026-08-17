# RFC: Explicit Query Authority And Cost

## Status

Possible future API cleanup. This is not implemented.

## Summary

Make query method names and builder surfaces reveal two facts:

1. which authorization or visibility boundary the operation applies
2. which parts of the query shape affect its cost and result

Avoid using `raw` to mean "storage-level and LOAD-privacy-skipping," and avoid
silently ignoring ordering, limits, or offsets when an aggregate API can make
those clauses unavailable or reject them.

## Motivation

The current `rawCount`, `rawExists`, and raw aggregate names are documented, but
`raw` commonly suggests raw SQL or an untyped escape hatch. In EntKt it instead
means:

- execute typed predicates through the ordinary driver
- run read interceptors
- skip entity materialization and LOAD privacy
- ignore ordering and, for most aggregates, limit and offset

That is a coherent contract, but the name does not state the authority boundary
or the ignored query shape.

The generic `ReadResult<T?>.visibleOrNull()` extension also type-checks on
nullable aggregate results even though it is meaningful only for root entity
LOAD denial. An inert but nonsensical call is unnecessary API surprise.

## Vocabulary

Use distinct words consistently:

- `storage`: operates on matching storage rows without LOAD privacy
- `visible`: applies query-time visibility predicates
- `checked` or `materialized`: hydrates entities and applies LOAD privacy
- `projected`: returns selected fields under its documented visibility posture
- `page` or `window`: states whether bounds are cursor-visible or raw storage
  bounds

The final names should be chosen after reference comparison. The essential rule
is that one word has one framework meaning.

## Candidate Surface

With query-time visibility:

```kotlin
client.posts.query {
    where(Post.published eq true)
}.count() // query-visible count
```

Trusted storage inspection is intentionally louder:

```kotlin
ctx.client.posts.storageQuery {
    where(Post.aclGroupId eq groupId)
}.count()
```

An alternative with less new hierarchy is:

```kotlin
query.storageCount()
query.storageExists()
```

Prefer `storage` over `unchecked`: the operation still validates identifiers,
runs interceptors, and captures failures, so it is not generally unchecked.

## Separate Row And Aggregate Shapes

Ordering and bounds are meaningful for row selection but ordinary SQL
`COUNT(*)`, `MIN`, and `MAX` over predicates usually ignore them in EntKt.

Possible designs:

### Aggregate Before Row Shaping

```kotlin
client.orders.aggregate {
    where(Order.status eq Status.SHIPPED)
}.sum(Order.total)
```

The aggregate builder simply has no `orderBy`, `limit`, eager-load, or traversal
terminal members that do not affect the metric.

### Reject Irrelevant Shape

Retain aggregate terminals on the query but fail if the caller configured a
clause the terminal ignores:

```text
rawCount does not use orderBy or offset; build an aggregate query without those
clauses or explicitly call countAllMatching().
```

### Explicitly Count A Window

If useful, provide a separate terminal whose SQL actually counts the selected
window:

```kotlin
query.countWindow()
```

It must have distinct SQL and privacy semantics rather than changing `count`
based on whether a limit happens to be present.

The recommended direction is structural separation where practical, with
validation errors for remaining ambiguous combinations.

## Singular Entity Absence

Privacy-as-absence should be available only on entity-shaped reads. Possible
generated APIs include:

```kotlin
client.posts.findVisibleById(id)
query.firstVisibleOrNull()
```

If the result-algebra design keeps `visibleOrNull`, generate or type it through
an entity-specific result wrapper so nullable aggregates do not expose it.

Names must preserve the current narrow rule: only root LOAD denial maps to
absence; eager denial and operational failure remain failures.

## Explainability

Every terminal should be able to report:

- effective predicates
- whether query visibility applied
- whether LOAD privacy will run
- whether entities are hydrated
- which configured clauses are used or rejected
- whether execution is native, emulated, or scanning

This metadata should be machine-readable so tests and observability do not parse
method names.

## Compatibility Direction

EntKt is greenfield, so prefer one clear canonical name over permanent aliases.
A short deprecation period is optional, but docs and examples should switch in
one change.

Generated member-collision validation must reserve the chosen terminal and
posture names before codegen.

## Non-Goals

- Do not remove trusted storage-level reads.
- Do not treat storage-level results as LOAD-authorized.
- Do not add untyped raw SQL through this RFC.
- Do not silently scan and materialize arbitrary rows to implement a count.
- Do not select a final vocabulary without comparing established ORM usage.

## Test Requirements

- storage-level and query-visible operations have distinct names and contracts
- irrelevant builder clauses are unavailable or rejected, never silently
  ignored
- window-count and all-matching count cannot be confused
- privacy-as-absence is unavailable on nullable aggregate results
- explain output declares visibility, LOAD, hydration, and clause usage
- application docs use only the canonical vocabulary
- generated collision diagnostics reserve every new fixed member

## Related Features

- [Query-Time Visibility Predicates](../privacy-validation/query-time-visibility-predicates.md)
- [Projection / Select API](projection-select-api.md)
- [Cursor Pagination](cursor-pagination.md)
- [Query Observability Diagnostics](query-observability-diagnostics.md)
