# RFC: Query Observability Diagnostics

## Status

Possible future feature. This is not implemented.

## Summary

Add query diagnostics that make generated read behavior inspectable before and
after execution.

The goal is to help users answer:

- how many database queries will this operation issue?
- which SQL shapes will be used?
- did eager loading batch edges or fall back to repeated reads?
- which interceptors or implicit predicates changed the query?
- did request-scoped loaders batch or cache reads?

## Motivation

entkt should be clear and non-surprising at the schema and query level. That
includes performance behavior.

Generated APIs can hide useful complexity:

```kotlin
client.posts.query {
    withAuthor()
    withComments()
}.all()
```

This may be the right API, but users still need to understand the work it does
under the hood. If eager loading, privacy filtering, request-scoped loading, or
interceptors add hidden reads, the framework should make that observable.

This RFC is about diagnostics and instrumentation. It does not itself change
query execution strategy.

## Non-Goals

- Do not require applications to enable logging globally.
- Do not expose sensitive bind values by default.
- Do not replace driver-specific SQL explain plans.
- Do not guarantee exact query counts for every dynamic privacy rule.
- Do not optimize N+1 behavior in this RFC.
- Do not require a GraphQL runtime.

## Proposed API

Add a dry-run-style query explanation surface. The exact method
name mirrors the terminal it models — there is no bare `.explain()`
because it would be ambiguous about which terminal it dry-runs
(every terminal has its own intercepted query context). Use the
per-terminal explain method:

```kotlin
val explanation = client.posts.query {
    where(Post.published eq true)
    withAuthor()
    limit(20)
}.explainAllOrThrow()
```

The full explain surface (one per terminal-API name) is
enumerated in
[Read-Path Interceptors](read-path-interceptors.md) — see
"Explain Interaction" for `explainFirstOrError` /
`explainVisibleAll` / `explainRawCount` /
`explainVisibleByIdOrNull` / etc.

Possible text output:

```text
PostQuery
  root: posts
    predicates:
      published = ?
      deleted_at IS NULL        added by SoftDeleteInterceptor
    order:
      created_at DESC
    limit: 20

  eager loads:
    author: batched belongsTo load by author_id

  estimated driver queries:
    1 root query
    1 author batch query
```

The runtime representation should also be machine-readable:

```kotlin
data class QueryDiagnostics(
    val root: QueryPlanNode,
    val estimatedDriverQueries: IntRange?,
    val warnings: List<QueryDiagnosticWarning>,
)
```

Use an `IntRange?` instead of a single integer when the count depends on
runtime data, privacy behavior, or driver capabilities.

## Execution Tracing

Add an optional execution trace API for real runs:

```kotlin
val trace = client.withQueryTracing {
    posts.query {
        withAuthor()
        limit(20)
    }.all()
}
```

The trace records what actually happened:

```kotlin
trace.driverQueries
trace.loaderBatches
trace.cacheHits
trace.privacyChecks
trace.interceptorApplications
```

This complements the explain family:

- `explainAllOrThrow()` / `explainFirstOrError()` / `explainVisibleCount()`
  / etc. describe planned generated behavior for one terminal without
  executing the query (see
  [Read-Path Interceptors → Explain Interaction](read-path-interceptors.md)
  for the full method list and per-terminal mirroring rule)
- tracing records actual driver calls, loader batches, cache hits, and
  privacy checks during execution

## Query Count Estimates

Diagnostics should report query counts in plain language:

```text
estimated driver queries: 2
```

or:

```text
estimated driver queries: 1..N
warning: LOAD privacy may issue additional reads from rule code
```

Generated eager loading can often produce stable estimates because the query
tree is known. Privacy rules and user hooks may issue arbitrary reads, so those
should be surfaced as uncertainty instead of hidden.

## SQL Shape Output

Driver-backed explanations can include SQL shapes and redacted bind values:

```text
SELECT * FROM posts
WHERE published = ? AND deleted_at IS NULL
ORDER BY created_at DESC
LIMIT ?
```

Default output should not include raw bind values because predicates may
contain emails, tokens, names, or other sensitive data.

Potential opt-in — every explain method takes the same
`includeBindValues` flag:

```kotlin
query.explainAllOrThrow(includeBindValues = true)
client.users.explainByIdOrNull(id, includeBindValues = true)
```

(The bind-values argument is uniform across the explain surface —
all `explain*` methods accept it. The interceptors RFC's
[Explain Interaction](read-path-interceptors.md) enumerates the
method names; this RFC owns the bind-values opt-in.)

## Loader And Eager-Load Diagnostics

Diagnostics should make batching visible:

```text
eager loads:
  comments: batched hasMany load by post_id
  author: batched belongsTo load by author_id
```

When request-scoped loaders are active, tracing should expose:

```text
loaders:
  User.loadMany: batch size 18
  User.load: cache hits 7
```

See [Request-Scoped Entity Loading](request-scoped-entity-loading.md).

## Interceptor Diagnostics

If read-path interceptors are adopted, both `explain()` and execution tracing
should show which interceptors ran and what they changed:

```text
interceptors:
  SoftDeleteInterceptor<Post>: added deleted_at IS NULL
  TenantReadInterceptor<Post>: added tenant_id = ?
```

See [Read-Path Interceptors](read-path-interceptors.md).

## Privacy Diagnostics

This RFC should stay separate from privacy explain mode.

Query diagnostics can count privacy checks and warn that privacy rules may
issue reads, but it should not replace rule-level tracing. Detailed privacy
decisions belong in [Privacy / Validation Explain Mode](../privacy-validation/privacy-validation-explain-mode.md).

Privacy-sensitive aggregate semantics are covered separately by
[Query Observability Privacy](../privacy-validation/query-observability-privacy.md).

## Test Requirements

Before implementation, add tests for:

- root query explanation includes table, predicates, order, limit, and offset
- eager-load explanation reports the expected generated batch reads
- interceptor-added predicates appear in diagnostics
- default SQL output redacts bind values
- execution tracing records actual driver query count
- loader tracing records batch sizes and cache hits
- privacy-rule reads are represented as uncertainty or trace events
- diagnostics are deterministic enough for snapshot tests
