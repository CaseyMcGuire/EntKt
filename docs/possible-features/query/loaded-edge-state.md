# RFC: Loaded Edge State

## Status

Ready for implementation. This is not implemented.

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

- unloaded means the query did not request the edge
- loaded-empty means the query requested the edge and produced no related rows

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

Add a public runtime type in `entkt.runtime.query`:

```kotlin
package entkt.runtime.query

sealed interface EdgeState<out T> {
    data object Unloaded : EdgeState<Nothing>
    data class Loaded<out T>(val value: T) : EdgeState<T>
}
```

The public helpers are:

```kotlin
val EdgeState<*>.isLoaded: Boolean

fun <T> EdgeState<T>.loadedOrNull(): EdgeState.Loaded<T>?
fun <T> EdgeState<T>.valueOrNull(): T?
fun <T> EdgeState<T>.requireLoaded(): T
```

`loadedOrNull()` returns the `Loaded` wrapper rather than its value. This
preserves the distinction between `Unloaded` and `Loaded(null)`.
`valueOrNull()` is the explicitly lossy convenience for callers that do not
need that distinction.

Generated edge containers stay non-null and carry `EdgeState` fields:

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

val posts = users.first().edges.posts.requireLoaded()
```

For an unloaded edge:

```kotlin
user.edges.posts.loadedOrNull() // null
user.edges.posts.valueOrNull()  // null
user.edges.posts.requireLoaded() // throws EntEdgeNotLoadedException
```

For a loaded to-many edge with no rows:

```kotlin
user.edges.posts.loadedOrNull() // EdgeState.Loaded(emptyList())
user.edges.posts.valueOrNull()  // emptyList()
```

## To-One Edges

Every to-one edge uses a nullable value inside `Loaded`:

```kotlin
val profile: EdgeState<Profile?>
val author: EdgeState<Author?>
```

This applies to `hasOne` and `belongsTo`, including a `belongsTo` whose
foreign key is required. A required foreign key guarantees that the source
row names a target; it does not guarantee that an eager-load subquery returns
that target. Eager-load predicates, read interceptors, `limit(0)`, or a
positive offset can all produce a loaded edge with no returned target.

Meanings:

- `Unloaded`: `withProfile()` was not requested
- `Loaded(null)`: the edge was requested but no target was returned
- `Loaded(profile)`: the relationship was loaded and has a target

The helpers preserve this distinction as follows:

```kotlin
user.edges.profile.loadedOrNull() // null when Unloaded; Loaded(null) when loaded-empty
user.edges.profile.valueOrNull()  // null in both cases
```

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
class EntEdgeNotLoadedException : IllegalStateException(
    "Edge was not loaded; eager-load it before calling requireLoaded()",
)
```

The exception does not require an entity or edge-name argument. Repeating a
generated name at the call site would be typo-prone, while storing names in
every state value would complicate a value type whose state is otherwise
fully represented by `Unloaded` or `Loaded(value)`.

This is a programming error, not an operational query failure. It is not an
`EntError`, is not an `EntException`, and has no `EntResult` variant.

## Generated and Runtime Boundaries

`EdgeState` applies to edge values on returned generated entities. It does
not replace the query builder's private nullable fields that record whether a
`with{Edge}` clause was configured.

The implementation has three primary code paths:

1. Add `EdgeState`, its helpers, and `EntEdgeNotLoadedException` to the public
   runtime query package.
2. Update entity generation so nested `Edges` properties default to
   `EdgeState.Unloaded`.
3. Update eager loading so every requested edge is assigned
   `EdgeState.Loaded(value)` for every returned parent:
   - direct to-many and many-to-many edges use a non-null list
   - `hasOne` and `belongsTo` edges use a nullable target

Nested eager loading keeps the same model recursively: the parent edge is
`Loaded`, while each returned target's own edge fields reflect which nested
`with{Edge}` clauses were requested.

## Prior Art

EntGo also keeps eager-loaded relationships under a nested `Edges` surface
and provides generated `...OrErr()` accessors plus `IsNotLoaded(...)` for
detecting unloaded relationships. This RFC keeps that explicit failure
behavior while using a Kotlin sealed value type, so callers can inspect state
without relying on a parallel loaded-bit mechanism:

- [EntGo eager loading](https://entgo.io/docs/eager-load/)
- [EntGo `ChildrenOrErr` / `IsNotLoaded` example](https://entgo.io/docs/tutorial-todo-gql-field-collection/)

## Migration Plan

This is a breaking generated-API change.

1. Introduce `EdgeState` runtime type and helpers.
2. Generate `EdgeState` fields instead of nullable edge fields.
3. Update eager-loading code to write `Loaded(value)`.
4. Regenerate and update compile-time and integration tests that currently
   use nullable edge checks.
5. Update the root README, codegen README, numbered API guides, and
   `example-spring`:
   - use `requireLoaded()` when the surrounding query always requests the edge
   - use `loadedOrNull()` when the caller needs to distinguish all states
   - use `valueOrNull()` only when collapsing unloaded and loaded-null is
     intentional

No compatibility shim or deprecation period is required. The project is
greenfield, and keeping nullable properties beside `EdgeState` would preserve
the ambiguity this RFC removes.

## Decisions

- Use one generic public `EdgeState<T>` rather than entity-specific wrappers.
- Keep the nested generated `Edges` data class.
- Keep edge state explicit; do not generate direct `user.posts` aliases.
- Make `requireLoaded()` parameterless.
- Make `loadedOrNull()` return `EdgeState.Loaded<T>?`.
- Provide the deliberately lossy `valueOrNull(): T?` separately.
- Put the state type, helpers, and exception in `entkt.runtime.query`.
- Keep `EntEdgeNotLoadedException` outside the `EntError` / `EntException`
  hierarchy.

## Test Requirements

### Runtime

- `Unloaded.isLoaded` is false and `Loaded(value).isLoaded` is true.
- `loadedOrNull()` returns `null` for `Unloaded`, `Loaded(null)` for a loaded
  nullable value, and `Loaded(value)` for a loaded non-null value.
- `valueOrNull()` returns the value and deliberately collapses `Unloaded` and
  `Loaded(null)`.
- `requireLoaded()` returns both non-null values and a loaded null.
- `requireLoaded()` throws `EntEdgeNotLoadedException` only for `Unloaded`,
  with the documented message and no `EntError` mapping.

### Code Generation

- Default generated edge properties are `EdgeState.Unloaded`.
- Direct to-many and M2M fields are `EdgeState<List<Target>>`.
- Every `hasOne` and `belongsTo` field is `EdgeState<Target?>`, regardless of
  foreign-key nullability.
- All eager assignment paths wrap their result in `EdgeState.Loaded(...)`.
- Query-builder `with{Edge}` configuration remains private and nullable.
- Entities without edges still do not generate an `Edges` class.

### Integration

- An edge not requested by the query remains `Unloaded`.
- Non-empty to-many and M2M loads produce `Loaded(list)`.
- Empty to-many and M2M loads produce `Loaded(emptyList())`.
- Present to-one loads produce `Loaded(target)`.
- Absent nullable to-one loads produce `Loaded(null)`.
- A required `belongsTo` filtered out by eager predicates, interceptors,
  `limit(0)`, or offset produces `Loaded(null)`, not `Unloaded`.
- Nested eager loads set state correctly at every requested level.
- Returned create/update entities retain the normal default unloaded state.
- Eager-loaded edge LOAD privacy behavior and interceptor execution are
  unchanged.
