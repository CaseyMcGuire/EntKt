# RFC: Phantom-Typed Query Scopes

## Status

Implemented on branch `rfc-phantom-typed-scopes` (phases 1-9). The
typed primitives in `:schema`, the runtime interceptor surface
typing, the generated codegen output, the postgres driver erasure,
the compile-fail test harness, and the cross-module `@EntktInternal`
opt-in restriction all ship together. Open question and migration
plan below are retained for historical context; "V1 lock-ins"
referenced throughout reflect the actual implementation choices.

> **Implementation summary.** Adds a phantom entity-scope type
> parameter `E` to `Predicate`, `Column` (+ subclasses), `OrderField`,
> `EdgeQuery`, and `EdgeRef`. Interceptor surface (`InterceptScope<E>`,
> `QueryShape<E>`, `TraversalSourceResult<E>`) types in `E`;
> `Driver` methods erase at the call boundary (`List<Predicate<*>>` /
> `List<OrderField<*>>`). `Predicate.HasEdge` / `HasEdgeWith` /
> `HasM2MEdgeFrom` and `EdgeRef` primary constructors carry
> `@EntktInternal` (`@RequiresOptIn(ERROR)`, `Retention.BINARY`)
> to close the edge-walker fabrication hole at the type-system
> layer. Generated codegen output is annotated
> `@file:OptIn(EntktInternal::class)`. Compile-fail tests in
> `schema/src/test/kotlin/entkt/query/PhantomScopeCompileFailTest.kt`
> pin every wrong-entity / opt-in / no-asc-on-ByteArray invariant.

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
}
```

Ordering helpers (`asc()` / `desc()`) stay scoped to columns whose
value type admits ordering — matching today's
`schema/.../Column.kt` design where ordering lives on
`ComparableColumn` / `EnumColumn` and not on the base `Column`. That
guard is what prevents `orderBy(col.asc())` on non-comparable
columns (`BytesColumn` — `ByteArray` doesn't implement
`Comparable`) at compile time. The phantom-scope rewrite preserves
the guard:

```kotlin
open class ComparableColumn<E : Any, T : Comparable<T>>(
    name: String,
) : Column<E, T>(name) {
    infix fun gt(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.GT, value)

    infix fun gte(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.GTE, value)

    infix fun lt(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.LT, value)

    infix fun lte(value: T): Predicate<E> =
        Predicate.Leaf(name, Op.LTE, value)

    fun asc(): OrderField<E> =
        OrderField(name, OrderDirection.ASC)

    fun desc(): OrderField<E> =
        OrderField(name, OrderDirection.DESC)
}
```

**Helper-parity invariant.** The phantom-scope rewrite preserves
every existing column helper and value-normalization rule from
`schema/.../Column.kt` — only the type parameter changes. Listed
explicitly so an implementer doesn't drop helpers by following
the sketches above literally:

- `Column<E, T>`: `eq`, `neq`, `in`, `notIn` (above)
- `ComparableColumn<E, T : Comparable<T>>`: inherits `eq` / `neq`
  / `in` / `notIn`; adds `gt`, `gte`, `lt`, `lte`, `asc()`,
  `desc()` (above)
- `StringColumn<E>` (`= ComparableColumn<E, String>`): inherits
  all of the above; adds `contains`, `hasPrefix`, `hasSuffix`
  (Op.CONTAINS, Op.HAS_PREFIX, Op.HAS_SUFFIX leaves)
- `EnumColumn<E : Any, T : Enum<T>>`: overrides `eq`, `neq`,
  `in`, `notIn` to **normalize the value to `.name`** at predicate
  construction (`Predicate.Leaf(name, Op.EQ, value.name)` —
  matching Column.kt:71-76); adds `asc()`, `desc()` that order
  lexicographically by `.name`. Drivers continue to receive
  `String` values, not `Enum<T>` instances — implementation must
  preserve this normalization or the wire format breaks for
  every enum column.
- Nullable variants (`NullableColumn<E, T>`, `NullableComparableColumn<E, T>`,
  `NullableStringColumn<E>`, `NullableEnumColumn<E, T>`):
  inherit their non-nullable parent's helpers AND mix in the
  `Nullable` marker, which enables the extension functions
  `fun <C> C.isNull()` / `fun <C> C.isNotNull()` where
  `C : Column<E, *>, C : Nullable`.

`EnumColumn<E : Any, T : Enum<T>>` likewise carries `asc()` /
`desc()` (enums sort lexicographically by `.name`, matching today's
behavior — enums are not `Comparable` at the value type but the
on-the-wire `.name` string is). `StringColumn<E>` inherits ordering
from `ComparableColumn<E, String>`. A plain `Column<E, ByteArray>`
(which is what `EntityGenerator` emits for `FieldType.BYTES` — see
`codegen/src/main/kotlin/entkt/codegen/EntityGenerator.kt:396-403`,
mapping `BYTES` through `Column` / `NullableColumn`, not a
dedicated subclass) extends only the base `Column<E, T>` (no
`Comparable` upper bound, so no ordering helpers) and rejects
`orderBy(...)` calls at compile time, preserving the runtime
carveout in `InMemoryDriver`'s comparator. The `BytesColumn`
name appears in an existing KDoc reference at
`schema/src/main/kotlin/entkt/query/Column.kt:31` as an
illustrative example but is not (and per this RFC, will not
become) a real class — `Column<E, ByteArray>` is the on-disk
shape.

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
    // Backing fields stay `private`. `internal` would not actually
    // hide them: generated `UserQuery` is emitted into the user's
    // application module (see `docs/01-getting-started.md`), so
    // `internal var` would be writable from user application code
    // in the same module, bypassing `where()` / `orderBy()`.
    //
    // Codegen reaches these private fields through two patterns:
    //   1. Deferred-traversal snapshot: a generated `@EntktInternal
    //      internal fun snapshotForTraversal(driver: Driver,
    //      client: EntClient?): UserQuery` method on this class
    //      accesses the private fields from inside the class
    //      (same-class private access is unaffected by `internal`
    //      boundaries). Defined below.
    //   2. Edge-predicate walker: emits
    //      `targetQ.where(typedInner)` (the public DSL) instead of
    //      writing the backing list directly — same effect, no need
    //      to widen visibility.
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

    /**
     * Generated traversal-snapshot helper. Marked `@EntktInternal`
     * so application code can't call it without an explicit
     * `@OptIn(EntktInternal::class)`. Lives inside the query class
     * so it can read `predicates` / `orderFields` directly without
     * widening their visibility.
     */
    @EntktInternal
    internal fun snapshotForTraversal(driver: Driver, client: EntClient?): UserQuery =
        UserQuery(driver, client).also {
            it.predicates = this.predicates
            it.orderFields = this.orderFields
            // ... queryLimit / queryOffset / traversal* fields ...
        }
}
```

## Constructor Visibility

The edge-predicate walker (see
[Edge-Predicate Walker](#edge-predicate-walker)) relies on
`HasEdgeWith<E, Target>` being constructed only at the typed
`EdgeRef.has { ... }` call site, so the edge-name string serves
as a runtime witness for the walker's unchecked
`predicate.inner as Predicate<Target>` cast. If application code
could fabricate
`Predicate.HasEdgeWith<User, Comment>("posts", commentPred)`
directly, the walker enters the `"posts" ->` branch, casts a
`Predicate<Comment>` to `Predicate<Post>` (succeeds at runtime
via erased generics), and sends Comment field names against the
Post table.

V1 closes that hole using Kotlin's standard `@RequiresOptIn`
opt-in mechanism. The annotation propagates across module
boundaries, which `internal` visibility cannot — `EdgeRef.has(...)`
lives in `:schema`, but generated codegen output is emitted into
the user's application module (see
`docs/01-getting-started.md`). An `internal` constructor on
`EdgeRef` in `:schema` would be unreachable from generated code
in the app module; an `internal` constructor on `EdgeRef` defined
in the user module would also be reachable from user application
code in the same module. Neither works. `@RequiresOptIn` does:

```kotlin
// In :schema
@RequiresOptIn(
    message = "Internal entkt construction site; direct fabrication " +
              "can bypass edge-walker soundness. Construct edge " +
              "predicates only via the generated EdgeRef.has(...) / " +
              "EdgeRef.exists() surface.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
annotation class EntktInternal
```

The following constructors carry `@EntktInternal`:

- `Predicate.HasEdge<E>` primary constructor
- `Predicate.HasEdgeWith<E, Target>` primary constructor
- `Predicate.HasM2MEdgeFrom<E, Source>` primary constructor
- `EdgeRef<Source, Target, Q>` primary constructor

`Predicate.Leaf<E>`, `Column<E, T>` and its subclasses, and
`OrderField<E>` primary constructors stay `public` — they don't
gate the walker-cast soundness story, and the residual hole
(wrong column name within the right entity) surfaces at
driver-render time, not via silent data-from-the-wrong-table.
The "Open Questions" section below records this as the locked-in
V1 visibility decision.

Codegen emits `@file:OptIn(EntktInternal::class)` at the top of
every generated `.kt` file that constructs edge predicates or
`EdgeRef`s. Application code attempting direct construction
raises a hard compile error with the annotation's message.
Advanced extension code that legitimately needs to construct
these types can use the same `@file:OptIn(EntktInternal::class)`
(or per-call `@OptIn(EntktInternal::class)`), making the unsafe
intent explicit and grep-able.

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

    // Edge-predicate constructors carry @EntktInternal — see
    // the "Constructor Visibility" section. Direct construction
    // from application code raises a compile error; codegen opts
    // in once via @file:OptIn(EntktInternal::class).
    //
    // These are regular `class`es (NOT `data class`), because a
    // data class auto-generates a PUBLIC `copy(...)` method that
    // is not covered by the constructor's opt-in annotation —
    // reopening the walker-cast fabrication hole. Manual
    // `equals` / `hashCode` / `toString` preserve value semantics
    // without re-introducing `copy()`.
    class HasEdge<E : Any> @EntktInternal constructor(
        val edge: String,
    ) : Predicate<E>() {
        override fun toString(): String = "HasEdge(edge=$edge)"
        override fun equals(other: Any?): Boolean =
            other is HasEdge<*> && other.edge == edge
        override fun hashCode(): Int = edge.hashCode()
    }

    class HasEdgeWith<E : Any, Target : Any> @EntktInternal constructor(
        val edge: String,
        val inner: Predicate<Target>,
    ) : Predicate<E>() {
        override fun toString(): String = "HasEdgeWith(edge=$edge, inner=$inner)"
        override fun equals(other: Any?): Boolean =
            other is HasEdgeWith<*, *> && other.edge == edge && other.inner == inner
        override fun hashCode(): Int = 31 * edge.hashCode() + inner.hashCode()
    }

    class HasM2MEdgeFrom<E : Any, Source : Any> @EntktInternal constructor(
        val sourceTable: String,
        val edgeName: String,
        val sourceFilter: Predicate<Source>?,
    ) : Predicate<E>() {
        override fun toString(): String =
            "HasM2MEdgeFrom(sourceTable=$sourceTable, edgeName=$edgeName, " +
                "sourceFilter=$sourceFilter)"
        override fun equals(other: Any?): Boolean =
            other is HasM2MEdgeFrom<*, *> &&
                other.sourceTable == sourceTable &&
                other.edgeName == edgeName &&
                other.sourceFilter == sourceFilter
        override fun hashCode(): Int {
            var h = sourceTable.hashCode()
            h = 31 * h + edgeName.hashCode()
            h = 31 * h + (sourceFilter?.hashCode() ?: 0)
            return h
        }
    }
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

Edge predicates are the intentional scope bridge. Two interfaces
mediate them on the generated query class:

```kotlin
// Internal: the query class folds its accumulated wheres into a
// single predicate for the runtime to lower into an EXISTS
// subquery. `combinedPredicate()` is consumed by `EdgeRef.has`
// after the block runs.
interface EdgeQuery<E : Any> {
    fun combinedPredicate(): Predicate<E>?
}

// Narrow public DSL surface visible inside `EdgeRef.has { ... }`
// blocks. Exposes ONLY `where(Predicate<E>)` so calls that don't
// lower into the runtime's EXISTS subquery (`orderBy` / `limit` /
// `offset` / traversal `queryX()` / eager `with{Edge}` / terminal
// operations) are unreachable inside the block at compile time.
interface EdgePredicateScope<E : Any> {
    fun where(predicate: Predicate<E>): EdgePredicateScope<E>
}
```

```kotlin
// Primary constructor carries @EntktInternal — see the
// "Constructor Visibility" section. The has(...) / exists() body
// constructs Predicate.HasEdge and Predicate.HasEdgeWith
// (both also @EntktInternal); EdgeRef lives in :schema so those
// calls are inside the opt-in boundary established by :schema's
// own @file:OptIn(EntktInternal::class) on EdgeRef.kt.
//
// `Q` carries TWO constraints: it must be the target's
// EdgeQuery<Target> (so has() can read combinedPredicate()) AND
// EdgePredicateScope<Target> (so has() can use the narrow scope
// as the lambda receiver). Generated query classes implement both.
class EdgeRef<Source : Any, Target : Any, Q> @EntktInternal constructor(
    val name: String,
    private val newQuery: () -> Q,
) where Q : EdgeQuery<Target>, Q : EdgePredicateScope<Target> {
    fun exists(): Predicate<Source> =
        Predicate.HasEdge(name)

    fun has(block: EdgePredicateScope<Target>.() -> Unit): Predicate<Source> {
        val q = newQuery()
        q.block()
        val inner = q.combinedPredicate() ?: return Predicate.HasEdge(name)
        return Predicate.HasEdgeWith<Source, Target>(name, inner)
    }
}
```

Why the narrow receiver: `has(...)` lowers into a runtime
`HasEdgeWith` predicate whose inner is a `Predicate<Target>` —
the runtime doesn't take any order / limit / traversal / eager-
load parameters on that subquery. Calls to those methods inside
the block were either silently dropped (`orderBy`, `limit`) or
threw at runtime via `NoopDriver` (terminal ops like `allOrThrow`,
since the EdgeRef factory hands the target query a `NoopDriver`).
Narrowing the receiver makes those misuses compile errors instead.

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

## Interceptor Surface

The Read-Path Interceptors framework already parameterizes
`QueryInterceptor<E>` / `InterceptScope<E>` / `QueryShape<E>` by
the candidate entity, but several members still take or expose
**untyped** predicates and order fields — leaving a hole where
interceptors can inject wrong-entity predicates even after the
root `where(...)` surface is fixed. This RFC fills that hole by
typing the interceptor surface uniformly:

```kotlin
interface InterceptScope<E : Any> {
    val shape: QueryShape<E>

    /** Adds a predicate AND-ed with caller / prior-interceptor / structural predicates. */
    fun addPredicate(predicate: Predicate<E>)

    // requireLimitAtMost / setDefaultLimitIfAbsent /
    // rejectIfLimitGreaterThan / addAnnotation / reject(...)
    // signatures are unaffected — they don't carry predicates or
    // order fields.
}

data class QueryShape<E : Any>(
    val predicates: List<Predicate<E>>,
    val orderBy: List<OrderField<E>>,
    val limit: Int?,
    val offset: Int?,
    val annotations: Map<String, String>,
    // ...
)

interface QueryInterceptor<E : Any> {
    fun intercept(scope: InterceptScope<E>, context: QueryContext)
}
```

A `QueryInterceptor<User>` then cannot accidentally call
`scope.addPredicate(Post.published eq true)` — the predicate
type is `Predicate<Post>`, the scope expects `Predicate<User>`,
the call fails at compile time. Wrong-entity predicates from
prior-interceptor injection are caught at the same boundary as
wrong-entity predicates from caller `where(...)` mistakes.

**`GlobalInterceptScope` stays erased AND has no `addPredicate`.**
Global interceptors run across every entity, so
`GlobalInterceptScope` cannot carry a single `E` parameter. Its
`shape` remains `UntypedQueryShape`, which exposes only shape
*metadata* — predicate counts (total + per-attribution-bucket via
`callerPredicateCount` / `structuralPredicateCount` /
`interceptorPredicateCount`), `hasOrderBy: Boolean`, `limit` /
`callerLimit` / `offset`, and `annotations: Map<String, String>`.
There are no `Predicate<*>` / `OrderField<*>` lists on
`UntypedQueryShape`; the runtime documents this deliberate
choice (see `Interceptors.kt:171-189` / `read-path-interceptors.md`
§"UntypedQueryShape"): typed predicate references can't be safely
projected through a typeless surface, so globals get count-and-
presence metadata sufficient for defensive policies like "reject
unscoped broad `rawCount`" without leaking typed references.

Critically, `GlobalInterceptScope` deliberately omits
`addPredicate` — globals cannot inject predicates because no
`E` is available to type a predicate against (see
`Interceptors.kt:104-107`: "Mirrors `InterceptScope` minus
`addPredicate` (entity-typing isn't available globally)").
Globals can clamp limits (`requireLimitAtMost` /
`setDefaultLimitIfAbsent` / `rejectIfLimitGreaterThan`), add
annotations (`addAnnotation(key, value)` — both `String`), and
reject (`reject(reason, code)`) — but predicate shaping is
strictly a per-entity interceptor capability. The asymmetry is
deliberate: per-entity interceptors get type safety on predicates;
cross-cutting global interceptors get neither typed predicates
nor any predicate-injection surface at all.

## Query Traversal

Generated traversal methods should preserve the same typing.

> **Scope of the sketches below.** The Read-Path Interceptors RFC
> defines the actual generated shape of `queryX()`: it does **not**
> compute the source bridge eagerly from
> `combinedPredicate()` at `queryX()` time; instead it stashes a
> deferred lambda on the target query that calls
> `sourceQ.runReadInterceptors(EDGE_TRAVERSAL, QUERY)` at terminal
> time and returns a `TraversalSourceResult` carrying both the
> bridge and the source-step annotations
> (see `read-path-interceptors.md` §"Source state is snapshotted at
> `queryX()` time" and `codegen/.../QueryTraversalMembers.kt`). That
> deferral is what lets `*OrError` catch source-step rejections as
> `Err(QueryRejected)` and applies source tenant / soft-delete /
> max-limit interceptors. Since the shape-preserving traversal RFC
> (`edge-traversal-source-shape`), the bridge embeds the **whole**
> post-interceptor source shape (predicates, orderBy, limit,
> offset, flags) as a `TraversalSourceShape<Source>` inside
> `Predicate.HasEdgeFromShape` / `Predicate.HasM2MEdgeFromShape`
> rather than folding only the predicates into `HasEdgeWith` /
> `HasM2MEdgeFrom`. The code blocks in this section illustrate
> **only the typing changes**, not the deferred-execution control
> flow; the same typing applies inside the deferred lambda.

For a direct edge traversal:

```kotlin
client.users
    .query { where(User.active eq true) }
    .queryPosts()
```

`UserQuery.queryPosts()` returns a `PostQuery`. The deferred lambda
that the read-path-interceptors RFC describes produces a
`TraversalSourceResult<Post>` whose bridge is target-scoped:

```kotlin
// Type-shape sketch. The real generated code wraps this in a
// deferredSourceStep lambda that fires at terminal time.
val sourceSpec: FrozenQuerySpec<User> =
    sourceQ.runReadInterceptors(EDGE_TRAVERSAL, QUERY)
val bridge: Predicate<Post> =
    Predicate.HasEdgeFromShape<Post, User>(
        "author",
        TraversalSourceShape(
            table = sourceSpec.table,
            selectedColumn = "id",
            predicates = sourceSpec.predicates,  // List<Predicate<User>>
            orderBy = sourceSpec.orderBy,        // List<OrderField<User>>
            limit = sourceSpec.limit,
            offset = sourceSpec.offset,
            flags = sourceSpec.flags,
        ),
    )
```

For M2M traversal, the candidate entity is the M2M target and the
embedded shape keeps the source scope:

```kotlin
val bridge: Predicate<Tag> =
    Predicate.HasM2MEdgeFromShape<Tag, Post>(
        "tags",
        TraversalSourceShape(/* … Predicate<Post> / OrderField<Post> lists … */),
    )
```

## Edge-Predicate Walker

The per-entity edge-predicate walker generated in
`QueryGenerator.kt` (`runEdgePredicateInterceptors`) recurses
through a `Predicate<E>` tree, branches on `is HasEdgeWith`,
dispatches by `predicate.edge` (a `String` matching the schema's
outgoing edge names), constructs a per-edge target query, and
assigns `predicate.inner` into the target query's predicate list
so the target's `runReadInterceptors(EDGE_PREDICATE, QUERY)`
fires on it.

Today `predicate.inner: Predicate` (untyped) and the assignment
compiles trivially. After this RFC types
`Predicate.HasEdgeWith<E, Target>.inner: Predicate<Target>`, the
recursive smart-cast from a `Predicate<E>` tree gives us
`Predicate.HasEdgeWith<E, *>` (Target erased to `*`) — so
`predicate.inner: Predicate<*>` and a direct assignment into
`PostQuery.predicates: MutableList<Predicate<Post>>` will not
compile.

The walker must use an **edge-name-validated unchecked cast** in
each per-edge branch:

```kotlin
// Generated for source = User, edge = "posts" → target = Post.
"posts" -> {
    val targetQ = PostQuery(driver, c)
    @Suppress("UNCHECKED_CAST")
    val typedInner = predicate.inner as Predicate<Post>
    targetQ.where(typedInner)
    // ... runReadInterceptors(EDGE_PREDICATE, QUERY) on targetQ ...
}
```

The cast is sound because `HasEdgeWith<E, Target>` carries
`@EntktInternal` on its primary constructor (see
[Constructor Visibility](#constructor-visibility)) and is therefore
constructed only at the typed `EdgeRef.has { ... }` call site
(`User.posts.has { ... } → HasEdgeWith<User, Post>("posts", inner)`).
The schema-resolved mapping from `edge.name` to the target
entity in codegen matches the type-level `Target` parameter by
construction; the walker entering the `"posts" ->` branch is the
runtime witness (the schema declares
`User.posts: EdgeRef<User, Post, …>`, so the edge-name string
`"posts"` only ever co-occurs with `Target == Post`); the
unchecked-cast suppression is the type-system acknowledgement
that the witness is exhaustive. Direct fabrication
(`Predicate.HasEdgeWith<User, Comment>("posts", commentPred)`)
fails to compile in application code without an explicit
`@OptIn(EntktInternal::class)` — the walker hole is closed at
the type-system layer.

The walker uses the **public `target.where(typedInner)` DSL** rather
than writing the target's `predicates` backing field directly,
because `UserQuery.predicates` is `private` (see the `UserQuery`
sketch in [Proposed API](#proposed-api) — `internal var` would be
visible to user code in the same generated module, defeating the
encapsulation). `where(typedInner)` is functionally equivalent
(both append to the predicate list) and keeps generated code on
the public DSL surface.

`@Suppress("UNCHECKED_CAST")` is emitted inside the per-edge
branch only — not at the function level — so the cast scope
matches the witness scope and a reader can see immediately why
the suppression is safe. Codegen never emits this pattern outside
of an edge-name `when` arm whose branch label came from the
schema's `schema.edges()` iteration.

The same pattern would apply to `Predicate.HasM2MEdgeFrom<E, Source>`
if a future generated path needs to recurse directly into its
`sourceFilter`. Today public M2M `has` / `hasWhere` predicates use
the normal `HasEdge` / `HasEdgeWith` walker path; `HasM2MEdgeFrom`
is traversal-internal bridge plumbing whose source query has already
run through `EDGE_TRAVERSAL`.

## Driver Boundary

Erasure happens **only at the final driver call** — for *every*
predicate/order-consuming `Driver` entry point, not just `query`.
Every layer above the driver — generated query classes,
`QuerySpecBuilder<E>`, `FrozenQuerySpec<E>` produced by
`runReadInterceptors`, `TraversalSourceResult<E>`, the
`deferredSourceStep` lambda — stays typed in `E`. Erasure at the
driver edge is fine because drivers only consume the structural
data (`Leaf.field`, `Leaf.op`, `Leaf.value`, edge names, source
table names) and do not introspect predicate types. Erasure
earlier would break the typed construction of
`Predicate.HasEdgeWith<E, Target>` / `Predicate.HasM2MEdgeFrom<E, Source>`
inside traversal and interceptor processing — those depend on
knowing both source and target scopes after the source-step
interceptors have run.

The full set of `Driver` methods that take predicates and/or
order fields (see `runtime/src/main/kotlin/entkt/runtime/Driver.kt`)
must accept the erased forms uniformly:

```kotlin
fun query(
    table: String,
    predicates: List<Predicate<*>>,
    orderBy: List<OrderField<*>>,
    limit: Int?,
    offset: Int?,
): List<Map<String, Any?>>

fun count(table: String, predicates: List<Predicate<*>>): Long

fun exists(table: String, predicates: List<Predicate<*>>): Boolean

fun updateMany(
    table: String,
    values: Map<String, Any?>,
    predicates: List<Predicate<*>>,
): Int

fun deleteMany(table: String, predicates: List<Predicate<*>>): Int

fun explainQuery(
    table: String,
    predicates: List<Predicate<*>>,
    orderBy: List<OrderField<*>>,
    limit: Int?,
    offset: Int?,
): QueryExplanation

fun explainCount(
    table: String,
    predicates: List<Predicate<*>>,
): QueryExplanation
```

Generated query classes pass their typed `List<Predicate<E>>` /
`List<OrderField<E>>` to these methods as `List<Predicate<*>>` /
`List<OrderField<*>>` — Kotlin's variance lets this happen
implicitly. Predicate rendering on the driver side continues to use
the existing runtime data:

- `Leaf.field`
- `Leaf.op`
- `Leaf.value`
- edge names
- source table names for inverse M2M traversal

This keeps the feature focused on compile-time API safety rather than driver
rewrites — every existing driver implementation (`InMemoryDriver`,
`PostgresDriver`) updates only its signatures, not its rendering
logic.

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

1. **`@EntktInternal` annotation** — add the
   `@RequiresOptIn(level = ERROR) annotation class EntktInternal`
   to `:schema` (see [Constructor Visibility](#constructor-visibility)).
   Lands first because every other typed shape that touches an
   `@EntktInternal` constructor needs the annotation in scope.
2. **Type primitives generic** — parameterize `Predicate`,
   `OrderField`, `Column` (and `ComparableColumn`, `StringColumn`,
   `EnumColumn`, `Nullable*` variants), `EdgeQuery`, and `EdgeRef`
   over `E : Any`. Apply `@EntktInternal constructor` to
   `Predicate.HasEdge` / `HasEdgeWith` / `HasM2MEdgeFrom` and the
   `EdgeRef` primary constructor.
3. **Type the interceptor surface** —
   `InterceptScope<E>.addPredicate(predicate: Predicate<E>)`,
   `QueryShape<E>.predicates: List<Predicate<E>>`,
   `QueryShape<E>.orderBy: List<OrderField<E>>`,
   `QueryInterceptor<E>.intercept(scope, context)`. Leave
   `GlobalInterceptScope` / `UntypedQueryShape` erased (see
   [Interceptor Surface](#interceptor-surface)). Type the
   internal spec/result types that flow between layers:
   `QuerySpecBuilder<E>`, `FrozenQuerySpec<E>`,
   `TraversalSourceResult<E>`.
4. **Update generated entity companions** to emit scoped column
   refs (`Column<User, Boolean>("active")`) and edge refs
   (`EdgeRef<User, Post, PostQuery>("posts") { … }`). Generated
   `.kt` files that construct `EdgeRef` or edge predicates carry
   `@file:OptIn(EntktInternal::class)`.
5. **Update generated query builders.** `where(predicate: Predicate<E>)`
   and `orderBy(field: OrderField<E>)` accept scoped arguments.
   Backing fields `predicates` / `orderFields` stay `private`
   (see the `UserQuery` sketch). Add the generated
   `@EntktInternal internal fun snapshotForTraversal(driver, client?): Q`
   method on each query class to support deferred-traversal
   snapshots without widening backing-field visibility.
6. **Rewrite the edge-predicate walker** (`QueryGenerator.kt`
   `runEdgePredicateInterceptors`). Per-edge branches use the
   public `targetQ.where(typedInner)` DSL with the edge-name-
   validated `@Suppress("UNCHECKED_CAST")` inner-cast pattern
   (see [Edge-Predicate Walker](#edge-predicate-walker)); they no
   longer assign `targetQ.predicates = ...` directly.
7. **Update the driver interface** to accept erased `Predicate<*>`
   and `OrderField<*>` on **every** predicate/order-consuming
   method — `query`, `count`, `exists`, `updateMany`,
   `deleteMany`, `explainQuery`, `explainCount`. See the
   "Driver Boundary" section above for the full enumerated
   signatures.
8. **Update runtime and Postgres renderers mechanically.** Their
   logic should not need semantic changes — predicate render
   continues to use `Leaf.field` / `Leaf.op` / `Leaf.value` /
   edge names / source table names.
9. **Add compile-fail coverage** for wrong-scope predicates, order
   fields, and direct `@EntktInternal` constructor calls from
   un-opted-in application code (see "Compile-fail test harness"
   below in [Test Requirements](#test-requirements)).
10. **Update docs and examples** to show scoped edge predicates
    and the generated `@file:OptIn(EntktInternal::class)` shape
    in any code samples that touch generated code.

Because entkt is still greenfield, the first implementation can prefer the
clean generic API over compatibility aliases.

## Open Questions

- ~~Should the scope type be the generated entity class (`User`) or a
  generated marker type (`UserScope`)?~~ **Resolved: generated entity
  class.**

  Decision: the phantom scope type is the generated entity class
  itself (`User`, `Post`, `Comment`, etc.). The entire RFC body
  already assumes this — every code block uses `Predicate<User>`,
  `Column<User, Boolean>`, `EdgeRef<User, Post, …>`, etc. A marker
  type (`UserScope`) was considered for decoupling query APIs from
  data-class names but adds a generated type per entity without
  clear V1 benefit. If a future feature needs to break the
  data-class/scope-type identity (e.g. for builder-vs-entity-shape
  distinctions), it can introduce a marker type at that point
  without an incompatible churn — the phantom is a type parameter,
  so swapping out `User` for `UserScope` later is a mechanical
  rewrite of generated companions.

- Should `unsafeWhere` exist?

  It may be useful for advanced extension code, but it weakens the clarity of
  the public query API. The recommended V1 is to avoid it unless a concrete
  internal use case appears during implementation.

- ~~Should the raw string-backed constructors be public?~~
  **Resolved.** See [Constructor Visibility](#constructor-visibility)
  for the full mechanism. V1 lock-in:

  - **`@EntktInternal` (opt-in required):** `Predicate.HasEdge<E>`,
    `Predicate.HasEdgeWith<E, Target>`,
    `Predicate.HasM2MEdgeFrom<E, Source>`,
    `EdgeRef<Source, Target, Q>` primary constructors. Closes the
    edge-walker soundness hole at the type-system layer.
  - **`public`:** `Column<E, T>(name: String)` and its subclasses
    (`ComparableColumn`, `StringColumn`, `EnumColumn`, `Nullable*`
    variants), `OrderField<E>(field, direction)`,
    `Predicate.Leaf<E>(field, op, value)`. Residual gap is
    wrong-column-name-within-the-right-entity, which surfaces at
    driver-render time — softer failure mode and worth the
    friction tradeoff for tests / advanced extension code.

  No `unsafe*` named factory surface is introduced in V1. The
  `@OptIn(EntktInternal::class)` channel itself serves as the
  documented escape hatch; if extension-code patterns later
  motivate named unsafe factories, they can be added without
  breaking the V1 design.

## Test Requirements

**Compile-fail test harness.** Several bullets below assert that
specific code "fails to compile." Kotlin does not natively support
inline compile-fail tests, and the entkt repo has no existing
compile-fail infrastructure today (no `kotlin-compile-testing`
dependency, no compile-fail gradle task, no negative-test corpus
under any module). The V1 implementation must land a harness in
the same change set as the typed-scope rewrite — punting on the
harness means the most important validation (the "cannot do this
wrong" assertions) becomes a TODO.

**V1 mechanism (approved):** depend on
**`com.github.tschuchortdev:kotlin-compile-testing`** as a
`testImplementation` dependency on `:schema` (and optionally
`:codegen` for cross-module checks). Test-time only — not on the
production classpath, so no runtime footprint cost. Add a
dedicated `compileFailTests` source set where each test invokes
`KotlinCompilation` against a small Kotlin snippet, asserts
`exitCode != OK`, and matches the compiler's diagnostic against a
substring. Example shape:

```kotlin
@Test
fun `wrong-entity predicate fails to compile`() {
    val src = SourceFile.kotlin("Test.kt", """
        import entkt.query.*
        fun bad(q: UserQuery) {
          q.where(Post.published eq true)  // should fail
        }
    """.trimIndent())
    val result = KotlinCompilation().apply {
        sources = listOf(src)
    }.compile()
    assertNotEquals(OK, result.exitCode)
    assertTrue(result.messages.contains("Type mismatch"))
}
```

For `@EntktInternal` opt-in failures, match the substring "This
declaration is opt-in and its usage must be marked". Snippets
live as `.kt` strings inside JUnit tests, not as sibling files,
to keep compile-fail tests self-contained and discoverable from
the test report.

Alternative considered: a custom gradle task that runs `kotlinc`
on snippet files and grep-matches the output. Rejected for V1 —
adds bespoke infrastructure and bypasses the JUnit reporting that
the rest of the test suite uses.

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
- M2M traversal emits target-scoped `HasM2MEdgeFromShape` predicates
  (`HasM2MEdgeFrom` at the time of this RFC; upgraded by the
  shape-preserving traversal RFC)
- `isNull()` / `isNotNull()` preserve the receiver entity scope
- runtime and Postgres drivers render scoped predicates identically to the old
  unscoped representation
- `Column<E, ByteArray>` (the shape emitted for `FieldType.BYTES`
  by `EntityGenerator`) does NOT expose `asc()` / `desc()`;
  `orderBy(bytesCol.asc())` fails at compile time (regression
  test for the existing in-memory comparator carveout — no
  dedicated `BytesColumn` class exists or is introduced by this
  RFC)
- column-helper-parity tests: `ComparableColumn<E, T>` exposes
  `gt` / `gte` / `lt` / `lte` (not just `gt`); `StringColumn<E>`
  exposes `contains` / `hasPrefix` / `hasSuffix`; `EnumColumn<E, T>`
  serializes values via `.name` on every override
  (`Predicate.Leaf(name, Op.EQ, value.name)` etc.), matching
  Column.kt:71-76 — drivers continue to receive `String` enum
  values, never `Enum<T>` instances (regression test pinning the
  wire format)
- `@EntktInternal` is defined in `:schema` with
  `level = RequiresOptIn.Level.ERROR` and
  `Retention = BINARY` (annotation propagates across module
  boundaries)
- `Predicate.HasEdge<E>`, `Predicate.HasEdgeWith<E, Target>`,
  `Predicate.HasM2MEdgeFrom<E, Source>`, and
  `EdgeRef<Source, Target, Q>` primary constructors carry
  `@EntktInternal`; direct construction from application code
  (without `@OptIn(EntktInternal::class)`) fails to compile with
  the annotation's message — specifically
  `Predicate.HasEdgeWith<User, Comment>("posts", commentPred)`
  in an unannotated application file raises a compile ERROR
- generated `.kt` files that construct edge predicates or
  `EdgeRef`s carry `@file:OptIn(EntktInternal::class)`; these
  files compile cleanly (regression test pinning the codegen
  emit pattern)
- explicit `@OptIn(EntktInternal::class)` on an application
  call site succeeds in constructing a restricted type
  (the documented escape hatch path remains open for advanced
  extension code)
- `UserQuery.predicates` and `UserQuery.orderFields` backing
  fields are `private` (not `internal`); user application code
  in the same module cannot mutate them directly
  (`userQuery.predicates = ...` fails to compile — regression
  test pinning the encapsulation)
- the generated edge-predicate walker uses
  `targetQ.where(typedInner)` (the public DSL) rather than
  assigning `targetQ.predicates = ...`; semantic equivalence is
  preserved (the EDGE_PREDICATE walker still runs target
  interceptors as before — see the existing EDGE_PREDICATE
  walker regression test bullet above)
- the generated `snapshotForTraversal(driver, client)` method on
  each query class is marked `@EntktInternal internal`, accesses
  `private` `predicates` / `orderFields` from inside the same
  class (legal Kotlin), and is callable from generated traversal
  code via `@OptIn(EntktInternal::class)` but not from user
  application code without the opt-in
- `InterceptScope<E>.addPredicate(Predicate<E>)` accepts a typed
  predicate; a `QueryInterceptor<User>` that calls
  `scope.addPredicate(Post.published eq true)` fails at compile
  time
- `QueryShape<E>.predicates` is `List<Predicate<E>>` and
  `QueryShape<E>.orderBy` is `List<OrderField<E>>` (visible from
  inside `intercept`)
- `GlobalInterceptScope.shape` is `UntypedQueryShape` and exposes
  predicate counts (`predicateCount` plus the
  caller/structural/interceptor split) and `hasOrderBy: Boolean`
  metadata — **not** `Predicate<*>` / `OrderField<*>` lists
  (regression test pinning that no list accessor exists on the
  global shape)
- `GlobalInterceptScope` deliberately has **no** `addPredicate`
  member; a `GlobalQueryInterceptor` whose body calls
  `scope.addPredicate(...)` fails at compile time (regression
  test pinning the absence — globals can clamp limits, add
  annotations, and reject, but cannot inject predicates)
- the `deferredSourceStep` lambda generated by
  `QueryTraversalMembers.kt` continues to call
  `sourceQ.runReadInterceptors(EDGE_TRAVERSAL, QUERY)` and build
  the bridge from `sourceSpec` (since the shape-preserving
  traversal RFC: the full shape, not just `sourceSpec.predicates`)
  — typed-RFC change does NOT regress to eager
  `combinedPredicate()` bridging (regression test for the
  deferred-execution contract)
- `runEdgePredicateInterceptors` still compiles after the
  typed-`HasEdgeWith` rewrite: generated per-edge branches use
  the edge-name-validated `@Suppress("UNCHECKED_CAST")` pattern
  on `predicate.inner as Predicate<Target>` (see "Edge-Predicate
  Walker" section). Suppression is scoped to the `when` arm, not
  the function.
- the EDGE_PREDICATE walker continues to run target-entity
  interceptors on `HasEdgeWith` inner predicates after the typed
  rewrite — e.g. a soft-deletable `Post` accessed via
  `User.posts.has { ... }` still has the framework `soft-delete`
  interceptor fire on its inner step (regression test for
  target-interceptor execution surviving the type-safety pass)
