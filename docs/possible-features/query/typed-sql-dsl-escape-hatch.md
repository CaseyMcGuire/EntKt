# RFC: Typed SQL DSL Escape Hatch

## Status

Possible future feature. This is not implemented.

## Summary

Add a lower-level typed SQL expression surface for query cases that do not fit
the generated entity query DSL.

The goal is not to replace generated queries. The goal is to provide a clear,
bind-safe, entity-scoped escape hatch before users have to drop all the way to
driver-specific raw SQL strings.

## Motivation

The generated query DSL should stay small and predictable, but real
applications eventually need queries such as:

- database functions
- case-insensitive matching beyond simple string helpers
- computed order fields
- JSON containment predicates
- array containment predicates
- dialect-specific expressions
- projection expressions that are not just entity columns

Without a first-class escape hatch, users either request every SQL feature in
the high-level DSL or bypass entkt with custom driver calls. Bypassing entkt
loses useful behavior:

- entity-scoped field references
- parameter binding
- diagnostics and explain output
- driver capability checks
- consistent privacy semantics around the surrounding query

## Non-Goals

- Do not build a full SQL query builder in V1.
- Do not replace generated `where(...)`, `orderBy(...)`, projection, or
  aggregation APIs.
- Do not make raw SQL strings the primary query API.
- Do not bypass LOAD privacy or read-path interceptors.
- Do not add joins as a general-purpose public API in V1.
- Do not include upsert or returning APIs in this RFC.

## Proposed API Shape

Introduce an expression model that is scoped to the current entity:

```kotlin
interface SqlExpr<E : Any, T>
interface SqlPredicate<E : Any>
interface SqlOrder<E : Any>

class SqlScope<E : Any> {
    fun <T> column(column: Column<E, T>): SqlExpr<E, T>

    fun lower(value: SqlExpr<E, String>): SqlExpr<E, String>
    fun <T> literal(value: T): SqlExpr<E, T>

    infix fun SqlExpr<E, String>.like(pattern: String): SqlPredicate<E>
    infix fun <T : Comparable<T>> SqlExpr<E, T>.gt(value: T): SqlPredicate<E>
    fun SqlExpr<E, Boolean>.isTrue(): SqlPredicate<E>

    fun <T> SqlExpr<E, T>.asc(): SqlOrder<E>
    fun <T> SqlExpr<E, T>.desc(): SqlOrder<E>

    fun <T> raw(
        sql: String,
        type: SqlType<T>,
        bind: List<SqlBind> = emptyList(),
    ): SqlExpr<E, T>
}
```

Generated queries can expose explicitly named escape-hatch hooks:

```kotlin
client.posts.query {
    where(Post.published eq true)
    whereSql {
        lower(column(Post.title)) like "%kotlin%"
    }
    orderBySql {
        lower(column(Post.title)).asc()
    }
}
```

The method names should make the boundary obvious: `whereSql`, `orderBySql`,
and `selectSql` are intentionally lower-level than `where`, `orderBy`, and
`select`.

## Scope And Type Safety

SQL expressions should retain the same entity phantom scope as normal
predicates:

```kotlin
whereSql {
    lower(column(User.email)) like "%example.com"
}
```

Inside `PostQuery`, `column(User.email)` should fail at compile time for the
same reason `where(User.email eq "...")` fails after phantom-typed query scopes.

Edge predicate blocks intentionally switch scope:

```kotlin
client.users.query {
    where(User.posts.has {
        whereSql {
            lower(column(Post.title)) like "%kotlin%"
        }
    })
}
```

The outer predicate is still `Predicate<User>`, but the inner SQL expression
scope is `Post`.

## Raw Fragments

Raw SQL fragments are useful, but they should be the sharpest tool in this
surface.

Rules for V1:

- raw fragments are expression fragments, not whole queries
- raw fragments must use bind parameters for values
- raw fragments must declare their result type
- raw fragments cannot name arbitrary tables through the typed API
- diagnostics mark fragments as raw and include the owning dialect if known

Example:

```kotlin
whereSql {
    raw<Boolean>(
        sql = "metadata @> ?::jsonb",
        type = SqlType.Boolean,
        bind = listOf(SqlBind.Json("""{"featured":true}""")),
    ).isTrue()
}
```

This is less ergonomic than a purpose-built JSON helper, which is the point.
The helper can be added later once a pattern is common.

## Driver Capability Checks

Some expressions are not portable. The expression model should carry a
capability requirement that drivers can validate before execution:

```kotlin
JsonContains
ArrayContains
CaseInsensitiveLike
WindowFunctions
```

If a query uses an unsupported expression, the generated query should fail with
a clear error before building invalid SQL:

```text
PostgresDriver supports JSON containment, but H2Driver does not.
Query used Post.metadata containsJson(...)
```

See [Driver Capability Matrix](../tooling/driver-capability-matrix.md).

## Privacy Behavior

SQL escape-hatch predicates and order expressions are still part of the root
query. They should run through the same read-path interceptors and terminal
privacy behavior as normal predicates.

Projection expressions require explicit privacy semantics, matching
[Projection / Select API](projection-select-api.md):

- raw projection can select directly only when that mode is clearly named
- visible projection hydrates or checks entities before exposing values
- diagnostics should state which mode ran

## Diagnostics

Explain output should include SQL expressions in the post-interceptor query
shape and should preserve bind redaction by default:

```text
predicates:
  published = ?
  lower(title) LIKE ?        added by user SQL expression
```

Raw fragments should be labeled:

```text
raw SQL fragment:
  metadata @> ?::jsonb
  bind values redacted
```

See [Query Observability Diagnostics](query-observability-diagnostics.md).

## Test Requirements

Before implementation, add tests for:

- wrong-entity columns are rejected in `whereSql`, `orderBySql`, and
  `selectSql`
- generated SQL uses bind parameters for expression values
- default diagnostics redact bind values
- unsupported expression capabilities fail with clear errors
- read-path interceptors still run before SQL expressions are lowered
- edge predicate SQL blocks use the target entity scope
- raw fragments cannot be used as whole-query replacements
