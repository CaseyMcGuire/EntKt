# RFC: Read-Path Interceptors

## Status

Possible future feature. This is not implemented.

## Summary

Add generated query interception hooks that can inspect, constrain, annotate,
or reject read queries before they hit the driver.

The first version should focus on predictable read-path policies:

- tenant scoping
- soft-delete filters
- max-limit guards
- query logging / tracing annotations
- defensive rejection of unsafe broad reads

## Motivation

Some read behavior should be applied consistently across every query for an
entity. Today those concerns either need to be repeated at each call site or
encoded inside privacy rules after rows are loaded.

That is surprising for concerns that are really query-shape concerns:

```kotlin
client.posts.query().all()
```

In a multi-tenant application, this should probably include the current
tenant predicate automatically. For a soft-deleted entity, the default query
should probably exclude deleted rows. For a public endpoint, an unbounded query
may need to fail before it can scan a large table.

Read-path interceptors make these policies explicit and centralized without
making every application read go through a custom repository method.

## Non-Goals

- Do not replace LOAD privacy.
- Do not let interceptors grant access to rows privacy would deny.
- Do not make interceptors mutate returned entities.
- Do not require every application to install interceptors.
- Do not support arbitrary SQL rewriting in the first version.
- Do not hide interceptor-added predicates from explain/debug output.

## Proposed API

Add an interceptor interface at the runtime/query layer:

```kotlin
interface QueryInterceptor<E : Any> {
    fun intercept(query: QuerySpec<E>, context: QueryContext): QuerySpec<E>
}
```

`QuerySpec` is a structured, immutable description of the generated query:

```kotlin
data class QuerySpec<E : Any>(
    val table: String,
    val predicates: List<Predicate<E>>,
    val orderBy: List<OrderField<E>>,
    val limit: Int?,
    val offset: Int?,
    val flags: Set<QueryFlag>,
)
```

Generated repositories apply configured interceptors before driver calls:

```kotlin
val spec = QuerySpec(
    table = "posts",
    predicates = predicates,
    orderBy = orderFields,
    limit = limit,
    offset = offset,
    flags = flags,
)

val intercepted = client.interceptors.posts.apply(spec, context)
driver.query(intercepted.toDriverQuery())
```

The exact implementation can stay internal, but the public contract should be
that interceptors see a stable query description before driver execution.

## Generated Registration

Interceptors can be registered globally or per entity:

```kotlin
val client = EntClient(
    driver = driver,
    interceptors = EntInterceptors {
        posts(TenantReadInterceptor<Post>())
        users(MaxLimitInterceptor<User>(defaultLimit = 100))
    },
)
```

A schema-level default can install framework-owned interceptors:

```kotlin
override fun mixins() = listOf(softDelete())
```

The soft-delete mixin could register the same internal filter that backs:

```kotlin
client.posts.query().all()
client.posts.query { withDeleted() }.all()
client.posts.query { onlyDeleted() }.all()
```

See [Soft Delete](../schema/soft-delete.md) for the entity-level behavior.

## Common Interceptors

### Tenant Scoping

```kotlin
class TenantReadInterceptor<E : Any>(
    private val tenantColumn: Column<E, TenantId>,
) : QueryInterceptor<E> {
    override fun intercept(
        query: QuerySpec<E>,
        context: QueryContext,
    ): QuerySpec<E> =
        query.where(tenantColumn eq context.viewer.tenantId)
}
```

Tenant scoping is not a substitute for privacy. It prevents accidental broad
queries and helps the database use tenant indexes, while privacy still makes
the final authorization decision.

### Soft Delete

Soft-delete filtering can be modeled as a generated interceptor:

```kotlin
deletedAt.isNull()
```

The query flags `withDeleted` and `onlyDeleted` let the generated query builder
alter that default behavior without making soft delete a special case in every
driver.

### Max Limit

```kotlin
MaxLimitInterceptor<User>(
    defaultLimit = 100,
    maxLimit = 500,
)
```

Possible behavior:

- if no limit is present, apply the default
- if limit exceeds the max, either clamp or throw
- if the query is marked as an internal/system query, allow opt-out

The first version should prefer throwing over silently clamping unless the
interceptor is explicitly configured to clamp.

### Logging And Tracing

Interceptors can attach annotations for query diagnostics:

```kotlin
query.annotate("operation", "feed.load")
```

Those annotations should appear in query observability output. See
[Query Observability Diagnostics](query-observability-diagnostics.md).

## Ordering

Interceptor order must be deterministic.

Recommended order:

1. generated framework interceptors from schema features
2. application entity interceptors, in registration order
3. application global interceptors, in registration order

Open question:

- Should global interceptors run before entity interceptors so applications can
  enforce a hard outer policy?

Whatever order is chosen should be visible in explain output.

## Privacy Semantics

Interceptors run before storage reads. LOAD privacy still runs after rows are
materialized.

This means interceptors can only reduce or reject the candidate query set.
They must not be described as authorization. A row that passes tenant and
soft-delete interceptors can still be denied by privacy.

## Interaction With Typed Query Scopes

This feature should build on typed predicates:

```kotlin
QueryInterceptor<User>
```

should only be able to add `Predicate<User>` values to a `User` query. That
keeps interceptor code aligned with the same query-scope safety as normal
call-site queries.

See [Phantom-Typed Query Scopes](phantom-typed-query-scopes.md).

## Test Requirements

Before implementation, add tests for:

- entity interceptor adds a predicate before driver execution
- interceptor-added predicates are combined with caller predicates
- interceptor order is deterministic
- soft-delete flags override only the soft-delete interceptor behavior
- max-limit interceptor rejects or clamps according to configuration
- LOAD privacy still runs after intercepted reads
- explain output shows applied interceptors and added predicates
