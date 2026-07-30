# RFC: Loaded Edge State

## Status

Possible future feature. This is not implemented.

## Summary

Replace nullable eager-edge properties with an explicit loaded-state wrapper.

Today generated entities use a non-null `edges: Edges = Edges()` container
with nullable per-edge properties:

```kotlin
user.edges.posts
```

The meaning is precise but awkward:

- `edges.posts == null`: this edge was not eager-loaded
- `edges.posts == emptyList()`: this edge was loaded and has no rows
- for a to-one edge, `null` is ambiguous between "not loaded" and
  "loaded, no related row"

This RFC proposes generated edge state values that make loaded vs unloaded a
first-class concept.

## Motivation

The current nullable shape forces users to remember two different null levels.
It is easy to accidentally treat "edge was not loaded" the same as "edge was
loaded and empty."

For relationship data, those states are meaningfully different:

- unloaded means the program has not asked the database for the edge
- loaded-empty means the database was queried and no related rows matched

The API should make that difference visible without requiring nested nullable
checks.

## Goals

- Make loaded/unloaded edge state explicit.
- Preserve the ability to distinguish loaded-empty from unloaded.
- Keep generated entity values immutable.
- Avoid implicit lazy loading on property access.
- Give callers a clear failure when they require an edge that was not loaded.

## Non-Goals

- Do not add automatic lazy loading.
- Do not add a process-global entity cache.
- Do not weaken eager-load privacy checks.
- Do not remove batch eager loading.

## Proposed API

Add a runtime type:

```kotlin
sealed interface EdgeState<out T> {
    data object Unloaded : EdgeState<Nothing>
    data class Loaded<T>(val value: T) : EdgeState<T>
}
```

Useful helpers:

```kotlin
fun <T> EdgeState<T>.loadedOrNull(): T?
fun <T> EdgeState<T>.requireLoaded(edgeName: String): T
val EdgeState<*>.isLoaded: Boolean
```

Generated edge containers become non-null and carry `EdgeState` fields:

```kotlin
data class User(
    val id: Long,
    val name: String,
    val edges: Edges = Edges(),
) {
    data class Edges(
        val posts: EdgeState<List<Post>> = EdgeState.Unloaded,
        val profile: EdgeState<Profile?> = EdgeState.Unloaded,
    )
}
```

Usage:

```kotlin
val users = client.users.query {
    withPosts()
}.allOrThrow()

val posts = users.first().edges.posts.requireLoaded("User.posts")
```

For an unloaded edge:

```kotlin
user.edges.posts.loadedOrNull() // null
user.edges.posts.requireLoaded("User.posts") // throws
```

For a loaded to-many edge with no rows:

```kotlin
user.edges.posts.loadedOrNull() // emptyList()
```

## To-One Edges

To-one edges need to preserve both "unloaded" and "loaded but no target":

```kotlin
val profile: EdgeState<Profile?>
```

Meanings:

- `Unloaded`: `withProfile()` was not requested
- `Loaded(null)`: the nullable relationship was loaded and has no target
- `Loaded(profile)`: the relationship was loaded and has a target

## To-Many Edges

To-many edges should use a non-null collection inside `Loaded`:

```kotlin
val posts: EdgeState<List<Post>>
```

Meanings:

- `Unloaded`: `withPosts()` was not requested
- `Loaded(emptyList())`: the edge was loaded and no rows matched
- `Loaded(listOf(...))`: the edge was loaded with rows

## Error Shape

`requireLoaded()` should throw a dedicated exception:

```kotlin
class EntEdgeNotLoadedException(
    val entity: String,
    val edge: String,
) : RuntimeException("Edge User.posts was not loaded; call withPosts() first")
```

This is a programming error, not a privacy denial or driver failure.

## Migration Plan

This is a breaking generated-API change.

1. Introduce `EdgeState` runtime type and helpers.
2. Generate `EdgeState` fields instead of nullable edge fields.
3. Update eager-loading code to write `Loaded(value)`.
4. Update docs and examples from `edges.posts` to
   `edges.posts.loadedOrNull()` or `edges.posts.requireLoaded(...)`.

## Open Questions

- Should `requireLoaded()` require the caller to pass an edge name, or should
  generated edge state wrappers carry the name?
- Should generated edge fields have entity-specific wrapper types such as
  `LoadedEdge<User, List<Post>>`, or is a simple generic `EdgeState<T>` enough?
- Should `Edges` remain a nested generated data class, or should each entity
  expose generated edge accessors directly?

## Test Requirements

Before implementation, add tests for:

- default generated edges are `Unloaded`
- eager-loaded to-many edges are `Loaded(list)`
- eager-loaded empty to-many edges are `Loaded(emptyList())`
- eager-loaded nullable to-one edges can be `Loaded(null)`
- `loadedOrNull()` distinguishes unloaded from loaded-empty through the wrapper
  state
- `requireLoaded()` throws for unloaded edges with a clear message
- eager-loaded edge LOAD privacy behavior is unchanged
