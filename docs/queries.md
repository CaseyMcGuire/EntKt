# Queries

The generated `{Entity}Query` builder provides a type-safe API for
filtering, ordering, paginating, traversing edges, and eager loading
related entities.

## Basic Usage

```kotlin
val users = client.users.query {
    where(User.active eq true)
    orderBy(User.name.asc())
    limit(10)
    offset(20)
}.all()
```

`.all()` returns a `List<User>`. Use `.firstOrNull()` for single results,
`.count()` for the number of matching rows, or `.exists()` to check
whether any match exists without loading data.

## Predicates

Predicates are built from the typed column references on the entity's
companion object. Each column type exposes operators appropriate for its
type.

### Comparison Operators

Available on all column types:

```kotlin
User.name eq "Alice"        // equal
User.name neq "Bob"         // not equal
User.name `in` listOf("Alice", "Bob")   // IN
User.name notIn listOf("Charlie")       // NOT IN
```

Available on typed enum columns:

```kotlin
Ticket.priority eq Priority.HIGH          // passes the enum's .name to the driver
Ticket.priority `in` listOf(Priority.LOW, Priority.MEDIUM)
```

Available on comparable columns (`Int`, `Long`, `Float`, `Double`, `Instant`):

```kotlin
User.age gt 18              // greater than
User.age gte 18             // greater than or equal
User.age lt 65              // less than
User.age lte 65             // less than or equal
```

Available on string columns:

```kotlin
User.email contains "example"       // LIKE '%example%'
User.email hasPrefix "alice"        // LIKE 'alice%'
User.email hasSuffix "@test.com"    // LIKE '%@test.com'
```

Available on nullable columns:

```kotlin
User.age.isNull()           // IS NULL
User.age.isNotNull()        // IS NOT NULL
```

### Compound Predicates

Combine predicates with `and` / `or`:

```kotlin
client.users.query {
    where(
        (User.active eq true) and (User.age gte 18)
    )
}

client.users.query {
    where(
        (User.age gte 65) or (User.email hasSuffix "@admin.example.com")
    )
}
```

Parentheses control precedence naturally since `and` / `or` are infix
functions that return `Predicate` values.

### Chaining `where()`

Multiple `where()` calls accumulate predicates -- they are AND'd together
at query time:

```kotlin
client.users.query {
    where(User.active eq true)
    where(User.age gte 18)
    // Equivalent to: active = true AND age >= 18
}
```

## Ordering

Use the `.asc()` and `.desc()` methods on column references:

```kotlin
client.users.query {
    orderBy(User.age.desc())
    orderBy(User.name.asc())
}
```

Multiple `orderBy()` calls add successive sort keys.

## Pagination

```kotlin
client.users.query {
    limit(10)
    offset(20)
}
```

## Count and Exists

Use `count()` to get the number of matching rows without loading them,
or `exists()` to check whether any match exists:

```kotlin
val activeUsers = client.users.query {
    where(User.active eq true)
}.count()  // → Long

val hasAdmins = client.users.query {
    where(User.role eq "admin")
}.exists()  // → Boolean
```

Both methods respect all accumulated `where()` predicates but ignore
`orderBy`, `limit`, and `offset` — they operate on the full filtered
result set. Under the hood, the Postgres driver emits
`SELECT COUNT(*)` and `SELECT EXISTS(SELECT 1 ...)` respectively,
so no rows are materialized.

## Edge Traversal

Query builders expose methods for traversing edges. Given a `User` with
a `to("posts", Post)` edge, the generated query builder has:

### `queryPosts()` -- follow an edge

Returns a new `PostQuery` scoped to the posts belonging to the matched
users:

```kotlin
val postsOfActiveUsers = client.users
    .query { where(User.active eq true) }
    .queryPosts()
    .all()  // List<Post>
```

### `has` / `hasWhere` -- edge predicates

Filter entities based on whether related entities exist:

```kotlin
// Users who have at least one post
client.users.query {
    where(User.posts.has())
}

// Users who have a published post
client.users.query {
    where(User.posts.has { where(Post.published eq true) })
}
```

Under the hood, edge predicates become `EXISTS (SELECT 1 FROM ...)` SQL
subqueries. For M2M edges, the subquery includes the junction table join.

## Eager Loading

By default, edge data is not loaded. Use `with{Edge}()` to batch-load
related entities alongside the main query:

```kotlin
val users = client.users.query {
    where(User.active eq true)
    withPosts {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
    }
}.all()

// Access loaded edges
users.forEach { user ->
    val posts: List<Post>? = user.edges?.posts
    // null  = withPosts() was not called
    // []    = loaded, but no matching posts
    // [...]  = loaded with data
}
```

Eager loading avoids N+1 queries by collecting all parent IDs from the
main query result, then batch-loading the related entities with a single
`IN (id1, id2, ...)` query.

### Nested Eager Loading

You can nest eager loads to load multiple levels of relationships:

```kotlin
val users = client.users.query {
    withPosts {
        where(Post.published eq true)
    }
}.all()
```

### The `Edges` Data Class

Each entity with edges gets a nested `Edges` data class:

```kotlin
data class User(
    val id: UUID,
    val name: String,
    // ...
    val edges: Edges?,
) {
    data class Edges(
        val posts: List<Post>?,
    )
}
```

- `user.edges` is `null` when no eager loading was requested
- `user.edges?.posts` is `null` for a specific edge that wasn't loaded
- An empty list means the edge was loaded but no related entities exist

## Transactions

Queries participate in transactions automatically when using a
transaction-scoped client:

```kotlin
client.withTransaction { tx ->
    val user = tx.users.create { name = "Alice"; email = "a@b.com" }.save()
    val posts = tx.posts.query {
        where(Post.authorId eq user.id)
    }.all()
    // Both operations run in the same transaction
}
```

If the block throws, the transaction rolls back. Nested
`withTransaction` calls reuse the existing transaction.
