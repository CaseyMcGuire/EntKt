# RFC: Phantom-Typed Query Scopes

## Status

Possible future feature. This is not implemented.

## Summary

Add a generated entity scope type to query predicates, order fields, columns,
and edge references so callers cannot accidentally use one entity's query
pieces inside another entity's query.

Today, column references erase down to plain column-name strings:

```kotlin
client.users.query {
    where(Post.published eq true) // compiles today
}
```

The predicate only carries `field = "published"`, so the mistake is discovered
late by the driver, or worse, it can target a same-named column on the wrong
table.

With phantom query scopes:

```kotlin
Post.published eq true // Predicate<Post>
User.active eq true    // Predicate<User>
```

and generated query builders accept only their own scope:

```kotlin
class UserQuery {
    fun where(predicate: Predicate<User>): UserQuery
    fun orderBy(field: OrderField<User>): UserQuery
}
```

The invalid `where(Post.published eq true)` call then fails at compile time.

## Motivation

The query DSL is intended to be clear and non-surprising. Entity companion
columns make queries look type-safe, but the current runtime representation is
not scoped to the entity that declared the column.

That creates several surprising cases:

- a predicate from the wrong entity can compile in a root query
- an order field from the wrong entity can compile in a root query
- a wrong-entity predicate can accidentally match a same-named column
- nested edge predicate blocks expose target query syntax but still rely on
  unscoped predicates internally
- diagnostics happen at runtime instead of at the call site where the mistake
  was made

The desired mental model is:

- `UserQuery.where(...)` accepts only `User` predicates
- `UserQuery.orderBy(...)` accepts only `User` order fields
- `User.posts.has { ... }` intentionally switches the inner block to `Post`
  scope and returns a `User` predicate
- query traversal intentionally bridges from source scope to target scope

## Non-Goals

- Do not change SQL rendering semantics.
- Do not make generated entity IDs entity-specific wrapper types.
- Do not prevent all logical mistakes inside a correctly scoped query.
- Do not solve query count, eager-loading, or batching behavior in this RFC.
- Do not expose raw untyped predicates as the normal public query surface.
- Do not add table aliases or join-scoped column references in V1.

## Proposed API

Parameterize query primitives by a phantom entity scope.

```kotlin
open class Column<E : Any, T>(val name: String) {
    infix fun eq(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.EQ, value)

    infix fun neq(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.NEQ, value)

    infix fun `in`(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.IN, values.toList())

    infix fun notIn(values: Collection<T>): Predicate<E> =
        Predicate.Leaf(name, Op.NOT_IN, values.toList())

    fun asc(): OrderField<E> =
        OrderField(name, OrderDirection.ASC)

    fun desc(): OrderField<E> =
        OrderField(name, OrderDirection.DESC)
}
```

```kotlin
open class ComparableColumn<E : Any, T : Comparable<T>>(
    name: String,
) : Column<E, T>(name) {
    infix fun gt(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.GT, value)
}
```

Generated entity companions use the generated entity type as the scope:

```kotlin
data class User(...) {
    companion object {
        val id: Column<User, UUID> = Column("id")
        val active: Column<User, Boolean> = Column("active")
        val name: StringColumn<User> = StringColumn("name")
    }
}
```

Generated queries accept only their entity scope:

```kotlin
class UserQuery : EdgeQuery<User> {
    private var predicates: List<Predicate<User>> = emptyList()
    private var orderFields: List<OrderField<User>> = emptyList()

    fun where(predicate: Predicate<User>): UserQuery {
        predicates = predicates + predicate
        return this
    }

    fun orderBy(field: OrderField<User>): UserQuery {
        orderFields = orderFields + field
        return this
    }

    override fun combinedPredicate(): Predicate<User>? =
        predicates.reduceOrNull { acc, p -> acc and p }
}
```

## Predicate Shape

`Predicate` becomes generic over the candidate entity it filters.

```kotlin
sealed class Predicate<E : Any> {
    infix fun and(other: Predicate<E>): Predicate<E> =
        And(this, other)

    infix fun or(other: Predicate<E>): Predicate<E> =
        Or(this, other)

    data class Leaf<E : Any>(
        val field: String,
        val op: Op,
        val value: Any?,
    ) : Predicate<E>()

    data class And<E : Any>(
        val left: Predicate<E>,
        val right: Predicate<E>,
    ) : Predicate<E>()

    data class Or<E : Any>(
        val left: Predicate<E>,
        val right: Predicate<E>,
    ) : Predicate<E>()

    data class HasEdge<E : Any>(
        val edge: String,
    ) : Predicate<E>()

    data class HasEdgeWith<E : Any, Target : Any>(
        val edge: String,
        val inner: Predicate<Target>,
    ) : Predicate<E>()

    data class HasM2MEdgeFrom<E : Any, Source : Any>(
        val sourceTable: String,
        val edgeName: String,
        val sourceFilter: Predicate<Source>?,
    ) : Predicate<E>()
}
```

The generic type is a compile-time marker. Runtime drivers can continue to
render the same `field`, `op`, and `value` data.

## Order Fields

`OrderField` gets the same phantom scope:

```kotlin
data class OrderField<E : Any>(
    val field: String,
    val direction: OrderDirection,
)
```

This rejects:

```kotlin
client.users.query {
    orderBy(Post.createdAt.desc())
}
```

because the argument is `OrderField<Post>`, not `OrderField<User>`.

## Nullable Columns

The nullable marker stays orthogonal to the entity scope.

```kotlin
interface Nullable

class NullableColumn<E : Any, T>(
    name: String,
) : Column<E, T>(name), Nullable

fun <E : Any, C> C.isNull(): Predicate<E>
    where C : Column<E, *>, C : Nullable =
    Predicate.Leaf(name, Op.IS_NULL, null)
```

The same pattern applies to `NullableComparableColumn`,
`NullableStringColumn`, and `NullableEnumColumn`.

## Edge Predicates

Edge predicates are the intentional scope bridge.

```kotlin
interface EdgeQuery<E : Any> {
    fun combinedPredicate(): Predicate<E>?
}
```

```kotlin
class EdgeRef<Source : Any, Target : Any, Q : EdgeQuery<Target>>(
    val name: String,
    private val newQuery: () -> Q,
) {
    fun exists(): Predicate<Source> =
        Predicate.HasEdge(name)

    fun has(block: Q.() -> Unit): Predicate<Source> {
        val inner = newQuery().apply(block).combinedPredicate()
            ?: return Predicate.HasEdge(name)
        return Predicate.HasEdgeWith<Source, Target>(name, inner)
    }
}
```

Generated entity companions include both source and target scopes:

```kotlin
data class User(...) {
    companion object {
        val posts: EdgeRef<User, Post, PostQuery> =
            EdgeRef("posts") { PostQuery(NoopDriver) }
    }
}
```

This remains valid:

```kotlin
client.users.query {
    where(User.posts.has {
        where(Post.published eq true)
    })
}
```

The outer `has` call returns `Predicate<User>`. The inner block is a
`PostQuery`, so it accepts only `Predicate<Post>`.

## Query Traversal

Generated traversal methods should preserve the same typing.

For a direct edge traversal:

```kotlin
client.users
    .query { where(User.active eq true) }
    .queryPosts()
```

`UserQuery.queryPosts()` returns a `PostQuery`, and the generated bridging
predicate is target-scoped:

```kotlin
fun queryPosts(): PostQuery {
    val parent: Predicate<User>? = combinedPredicate()
    val target = PostQuery(driver, client)
    if (parent != null) {
        target.where(Predicate.HasEdgeWith<Post, User>("author", parent))
    } else {
        target.where(Predicate.HasEdge<Post>("author"))
    }
    return target
}
```

For M2M traversal, the candidate entity is the M2M target and the source
filter keeps the source scope:

```kotlin
target.where(
    Predicate.HasM2MEdgeFrom<Tag, Post>(
        sourceTable = "posts",
        edgeName = "tags",
        sourceFilter = parent,
    ),
)
```

## Driver Boundary

The driver does not need the phantom type. It can accept erased predicates and
order fields:

```kotlin
fun query(
    table: String,
    predicates: List<Predicate<*>>,
    orderBy: List<OrderField<*>>,
    limit: Int?,
    offset: Int?,
): List<Map<String, Any?>>
```

Generated query classes can store scoped lists internally and pass them to the
driver as erased lists. Predicate rendering continues to use the existing
runtime data:

- `Leaf.field`
- `Leaf.op`
- `Leaf.value`
- edge names
- source table names for inverse M2M traversal

This keeps the feature focused on compile-time API safety rather than driver
rewrites.

## Unsafe Escape Hatch

If an untyped escape hatch is needed for tests, driver internals, or advanced
library code, it should be explicit:

```kotlin
fun unsafeWhere(predicate: Predicate<*>): UserQuery
fun unsafeOrderBy(field: OrderField<*>): UserQuery
```

These methods should not appear in normal guides. If generated, their KDoc
must say they bypass entity-scope checking.

V1 may omit these methods entirely and keep untyped predicates internal to
runtime tests.

## Migration Plan

This is source-breaking for code that constructs predicates manually or stores
them in untyped variables.

Recommended migration:

1. Make `Predicate`, `OrderField`, `Column`, `EdgeQuery`, and `EdgeRef`
   generic.
2. Update generated entity companions to emit scoped column and edge refs.
3. Update generated query builders to store and accept scoped predicates/order
   fields.
4. Update the driver interface to accept erased `Predicate<*>` and
   `OrderField<*>`.
5. Update runtime and Postgres renderers mechanically; their logic should not
   need semantic changes.
6. Add compile-fail coverage for wrong-scope predicates and order fields.
7. Update docs and examples to show scoped edge predicates.

Because entkt is still greenfield, the first implementation can prefer the
clean generic API over compatibility aliases.

## Open Questions

- Should the scope type be the generated entity class (`User`) or a generated
  marker type (`UserScope`)?

  Using the entity class is easier to read and requires fewer generated types.
  A marker type avoids tying query APIs to data-class names, but adds
  indirection without clear V1 benefit.

- Should `unsafeWhere` exist?

  It may be useful for advanced extension code, but it weakens the clarity of
  the public query API. The recommended V1 is to avoid it unless a concrete
  internal use case appears during implementation.

- Should raw `Predicate.Leaf` construction be public?

  If manual construction remains public, callers must write a type argument
  such as `Predicate.Leaf<User>("active", Op.EQ, true)`. That is still safer
  than today's unscoped construction, but normal code should use generated
  columns.

## Test Requirements

Before implementation, add tests for:

- generated entity columns include the entity scope type
- generated `where()` accepts `Predicate<Entity>`
- generated `orderBy()` accepts `OrderField<Entity>`
- wrong-entity root predicates fail to compile
- wrong-entity root order fields fail to compile
- `User.posts.has { where(Post.published eq true) }` compiles and returns a
  `Predicate<User>`
- wrong-entity predicates inside an edge block fail to compile
- direct edge traversal emits target-scoped bridging predicates
- M2M traversal emits target-scoped `HasM2MEdgeFrom` predicates
- `isNull()` / `isNotNull()` preserve the receiver entity scope
- runtime and Postgres drivers render scoped predicates identically to the old
  unscoped representation
