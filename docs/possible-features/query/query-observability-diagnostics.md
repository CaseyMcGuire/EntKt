# RFC: Query Observability Diagnostics

## Status

Possible future feature. This is not implemented.

Read-path interceptors already own the per-terminal dry-run explain surface and
the post-interceptor query shape. This RFC is now limited to future diagnostics
that are not covered by that implementation: execution tracing, query-count
estimates, SQL shape output, loader / eager-load diagnostics, and diagnostic
warnings.

## Summary

Add query diagnostics that make generated read behavior inspectable beyond the
existing per-terminal explain methods.

The goal is to help users answer:

- how many database queries will this operation issue?
- which SQL shapes will be used?
- did eager loading batch edges or fall back to repeated reads?
- which generated or framework behavior changed the query?
- did request-scoped loaders batch or cache reads?
- which terminal call produced each driver query?
- how can query behavior be logged safely in development or production?

## Motivation

entkt should be clear and non-surprising at the schema and query level. That
includes performance behavior.

Generated APIs can hide useful complexity:

```kotlin
client.posts.query {
    withAuthor()
    withComments()
}.allOrThrow()
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
- Do not log raw bind values by default.
- Do not require every application to use the same logging backend.

## Implemented Baseline

[Read-Path Interceptors](../../implemented-features/query/read-path-interceptors.md) already defines and
implements the dry-run explain family. Each explain method mirrors a concrete
terminal API because every terminal has its own intercepted query context.

This RFC should not introduce another explain surface or redefine the base query
plan model. Future diagnostics should enrich the existing explain output and add
runtime tracing for actual executions.

Baseline explain output can already describe the post-interceptor root query
shape:

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

Future diagnostics can add a richer machine-readable wrapper around that
existing plan:

```kotlin
data class QueryDiagnostics(
    val plan: QueryPlan,
    val estimatedDriverQueries: IntRange?,
    val sqlShapes: List<SqlShape>,
    val loaderPlans: List<LoaderPlan>,
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
    }.allOrThrow()
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
  [Read-Path Interceptors → Explain Interaction](../../implemented-features/query/read-path-interceptors.md)
  for the full method list and per-terminal mirroring rule)
- tracing records actual driver calls, loader batches, cache hits, and
  privacy checks during execution

## Query Logging

Add an opt-in query logger that receives structured query events rather than
preformatted strings:

```kotlin
client.withQueryLogging(QueryLogOptions()) {
    posts.query {
        withAuthor()
        limit(20)
    }.allOrThrow()
}
```

Potential event model:

```kotlin
data class QueryLogEvent(
    val operation: QueryOperation,
    val entity: String,
    val terminal: String,
    val sqlShape: String?,
    val bindValues: BindValueDisplay,
    val duration: Duration?,
    val rowCount: Int?,
    val source: QuerySource,
)
```

Default logging should redact bind values:

```text
SELECT * FROM posts WHERE title = ? LIMIT ?
binds: [redacted, redacted]
```

Applications can opt in to bind-value logging explicitly for local debugging:

```kotlin
QueryLogOptions(includeBindValues = true)
```

That opt-in should be deliberately named because bind values can contain
emails, names, tokens, or other sensitive application data.

## Slow Query Logging

Support threshold-based logging without requiring full tracing:

```kotlin
QueryLogOptions(
    slowQueryThreshold = 250.milliseconds,
)
```

Slow-query events should include:

- entity and terminal
- SQL shape
- elapsed time
- row count when the driver reports it
- loader/interceptor attribution when available

This should work for generated reads and mutation reads such as current-row
loads during updates.

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

Driver-backed diagnostics can include SQL shapes and redacted bind values:

```text
SELECT * FROM posts
WHERE published = ? AND deleted_at IS NULL
ORDER BY created_at DESC
LIMIT ?
```

Default output should not include raw bind values because predicates may
contain emails, tokens, names, or other sensitive data.

Potential opt-in: every explain / diagnostic method that can show SQL takes the
same `includeBindValues` flag.

```kotlin
query.explainAllOrThrow(includeBindValues = true)
client.users.explainByIdOrNull(id, includeBindValues = true)
```

The bind-values argument should be uniform across the existing explain surface
and any richer diagnostics API added by this RFC.

Typed SQL escape-hatch expressions should appear in this same SQL shape output.
Raw fragments should be marked so users can distinguish generated predicates
from user-supplied SQL fragments.

See [Typed SQL DSL Escape Hatch](typed-sql-dsl-escape-hatch.md).

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

The existing explain family already shows post-interceptor query shape. Future
diagnostics and execution tracing should add attribution for which interceptors
ran and what they changed:

```text
interceptors:
  SoftDeleteInterceptor<Post>: added deleted_at IS NULL
  TenantReadInterceptor<Post>: added tenant_id = ?
```

See [Read-Path Interceptors](../../implemented-features/query/read-path-interceptors.md).

## Privacy Diagnostics

This RFC should stay separate from privacy explain mode.

Query diagnostics can count privacy checks and warn that privacy rules may
issue reads, but it should not replace rule-level tracing. Detailed privacy
decisions belong in [Privacy / Validation Explain Mode](../privacy-validation/privacy-validation-explain-mode.md).

Privacy-sensitive aggregate semantics are covered separately by
[Checked Aggregate Privacy](../privacy-validation/checked-aggregate-privacy.md).

## Test Requirements

Before implementation, add tests for:

- diagnostics build on the implemented per-terminal explain surface rather than
  introducing separate terminal names
- eager-load diagnostics report the expected generated batch reads
- interceptor attribution appears in diagnostics and runtime traces
- default SQL output redacts bind values
- execution tracing records actual driver query count
- query logging emits structured events without requiring global logging
- slow-query logging filters below-threshold queries
- logged bind values are redacted by default and opt-in when shown
- loader tracing records batch sizes and cache hits
- privacy-rule reads are represented as uncertainty or trace events
- diagnostics are deterministic enough for snapshot tests
