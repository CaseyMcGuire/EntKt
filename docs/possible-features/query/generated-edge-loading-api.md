# RFC: Generated Edge Loading API

## Status

Accepted public API direction as of 2026-08-19. This is not implemented.

This RFC owns the generated API used to select entity edges for loading. It
does not own the execution algorithm. The accepted
[Set-Based Eager Graph Loader](set-based-eager-graph-loader.md) RFC defines how
an immutable edge-load plan is executed.

The
[Schema Declaration Names As Generated API](../schema/schema-declaration-api-names.md)
RFC defines where every `{Name}` in this document comes from.

## Summary

Replace generated `with{Name}` eager-loading methods with explicit
`load{Name}` methods:

```kotlin
val users = client.users.query {
    where(User.active eq true)

    loadPosts {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())

        loadComments()
    }
}.all().getOrThrow()
```

`loadPosts` means exactly this at the public API boundary:

> Include the `posts` relationship in the graph materialized for the returned
> users.

The name deliberately signals relationship-loading work beyond selection of
the root rows. It does not promise one extra statement, a SQL join, a specific
driver primitive, or a fixed execution order.

The generated method name comes from the delegated Kotlin edge declaration:

```kotlin
class User : EntSchema(
    tableName = "people",
    clientName = "users",
) {
    val authoredPosts by hasMany<Post>("written_post_rows")
}
```

This declaration generates `loadAuthoredPosts`, `queryAuthoredPosts`, and
`user.edges.authoredPosts`. It does not generate a name from
`written_post_rows`, `Post`, or an English pluralizer.

## Motivation

### `with{Name}` hides the operation

The current spelling is concise:

```kotlin
client.users.query {
    withPosts()
}
```

But `with` does not identify what happens. It can be read as a join, an
included predicate, a projection modifier, an entity-graph selection, or an
arbitrary companion value. A caller cannot tell from the method name that the
framework will materialize a relationship and may execute additional storage
queries.

`loadPosts()` states the operation directly. It also produces useful IDE
completion: typing `load` on a generated query lists the relationships that
can be materialized.

### A generic wrapper adds unnecessary nesting

A GraphQL-shaped wrapper was considered:

```kotlin
client.users.query {
    loadEdge {
        posts {
            where(Post.published eq true)
        }
    }
}
```

It makes the graph-selection boundary visible, but it adds a level of nesting
and weakens completion. After typing `loadEdge`, the caller still needs to
enter another scope before discovering generated relationships.

The generated method keeps the same clarity with less ceremony:

```kotlin
client.users.query {
    loadPosts {
        where(Post.published eq true)
    }
}
```

### Edge names are domain names

Relationships cannot be named reliably from their target type or cardinality:

```kotlin
val manager by belongsTo<User>("manager_fk")
val mentor by belongsTo<User>("mentor_fk")

val authoredPosts by hasMany<Post>("authored_post_rows")
val reviewedPosts by hasMany<Post>("reviewed_post_rows")
```

The useful API is `loadManager`, `loadMentor`, `loadAuthoredPosts`, and
`loadReviewedPosts`. Names such as `loadUser`, `loadPostEdges`, or
storage-derived names discard the semantic role already stated by the schema
author.

### Loading and traversal are different operations

EntKt already has generated `query{Name}` traversal. Traversal changes which
entity type the query returns. Edge loading keeps the root result and fills an
`EdgeState` member on it.

Those operations need parallel but distinct names:

```kotlin
// Returns posts. The users query is a relational source constraint.
val posts = client.users
    .query { where(User.active eq true) }
    .queryAuthoredPosts {
        where(Post.published eq true)
    }
    .all()

// Returns users. Each returned user's authoredPosts edge is loaded.
val users = client.users.query {
    where(User.active eq true)
    loadAuthoredPosts {
        where(Post.published eq true)
    }
}.all()
```

`query{Name}` and `load{Name}` make the result-root distinction readable at
the call site.

## Goals

- Make relationship materialization explicit in generated query code.
- Preserve IDE completion for every loadable edge.
- Derive method names solely from delegated Kotlin edge declarations.
- Use the same API shape for to-one, to-many, and many-to-many edges.
- Keep root-query configuration and target-edge configuration visually
  separate.
- Preserve `EdgeState` and eager LOAD privacy semantics.
- Reject ambiguous or silently ignored edge-load configuration.
- Keep the public selection API independent from the execution strategy.
- Make migration from `with{Name}` mechanical and source-reviewable.

## Non-Goals

- Do not define how many SQL statements an edge load executes.
- Do not promise joins, select-IN loading, breadth-first execution, or
  depth-first execution through the method name.
- Do not add implicit lazy loading.
- Do not select individual scalar fields as GraphQL does.
- Do not move `where`, `orderBy`, `limit`, or `offset` into method arguments.
- Do not infer edge names from target types, storage names, or cardinality.
- Do not add `loadEdge { ... }` or `loadEdges { ... }` wrapper scopes.
- Do not retain `with{Name}` as a permanent alias.
- Do not redefine edge mutation helpers.

## Terminology

### Root query

The generated query whose entity type is returned by the terminal:

```kotlin
client.users.query { /* UserQuery */ }.all()
```

### Edge-load selection

One invocation of a generated `load{Name}` method. It selects a relationship
to materialize on the root or on another selected edge.

### Target query

The generated query receiver passed to the `load{Name}` block. Its predicates,
ordering, bounds, interceptors, nested edge loads, and privacy posture describe
the target side of that edge.

### Edge-load plan

The immutable internal topology captured from all edge-load selections before
execution. This is executor input, not a public application result type.

### Edge-load handle

The value returned by `load{Name}`. It scopes optional behavior such as
`filterVisible()` to the exact selected edge.

## Generated API

### Method shape

For a `User.posts` edge targeting `Post`, codegen emits the conceptual API:

```kotlin
fun loadPosts(
    block: PostQuery.() -> Unit = {},
): EdgeLoad<UserQuery>
```

Both forms are valid:

```kotlin
loadPosts()

loadPosts {
    where(Post.published eq true)
    orderBy(Post.createdAt.desc())
    limit(10)
}
```

The zero-argument form loads the relationship using the target query's normal
defaults and configured interceptors.

The receiver block remains the complete generated target-query DSL. EntKt does
not create a second, reduced edge-options language and does not duplicate
query operations as method parameters.

### Generated name

`{Name}` is the edge declaration's generated stem as defined by the schema
naming RFC: uppercase only the first character of the delegated property name,
then add the fixed `load` prefix.

```text
posts            -> loadPosts
primaryAuthor    -> loadPrimaryAuthor
receivedRequests -> loadReceivedRequests
```

Codegen performs no other parsing or transformation. In particular, it does
not singularize, pluralize, translate, or normalize the declaration name.

The same declaration also names the companion edge, traversal, entity edge
state, explain path, and privacy-denial path:

```text
User.receivedRequests
queryReceivedRequests()
loadReceivedRequests()
user.edges.receivedRequests
```

### Cardinality

All supported relationship cardinalities use the same method family:

```kotlin
loadAuthor()       // belongs-to or has-one
loadPosts()        // has-many
loadTags()         // many-to-many
```

The name does not encode `One`, `Many`, `List`, `Edge`, or the target entity
type. Cardinality is already present in the generated target type and resulting
`EdgeState`:

```kotlin
post.edges.author  // EdgeState<User?>
user.edges.posts   // EdgeState<List<Post>>
post.edges.tags    // EdgeState<List<Tag>>
```

### Nested loading

Nested `load{Name}` calls select a graph without introducing a generic graph
builder:

```kotlin
client.users.query {
    loadPosts {
        where(Post.published eq true)

        loadComments {
            orderBy(Comment.createdAt.asc())
        }

        loadTags()
    }
}.all()
```

Every nested block is the generated query type for that relationship's target.
Ordinary Kotlin receiver completion therefore exposes its fields, query
operations, traversals, and loadable edges.

### Sibling loading

Several edges may be selected on one query:

```kotlin
client.users.query {
    loadProfile()
    loadAuthoredPosts()
    loadReceivedRequests()
}.all()
```

The order of these calls records the requested graph but does not define
physical query order, failure precedence, or attachment order. Those contracts
belong to the executor RFC. The accepted executor currently uses deterministic
schema-declaration depth-first order.

## `EdgeLoad` Handle

Rename the public `EagerLoad<ParentQuery>` handle to
`EdgeLoad<ParentQuery>`:

```kotlin
interface EdgeLoad<out ParentQuery> {
    fun filterVisible(): ParentQuery
}
```

The handle's name describes what it configures rather than one possible
execution strategy.

Ignoring the handle keeps strict eager LOAD privacy:

```kotlin
loadPosts()
```

Calling `filterVisible()` opts only that edge into filtering denied targets:

```kotlin
client.users.query {
    loadPosts {
        orderBy(Post.createdAt.desc())
        limit(10)
    }.filterVisible()

    limit(20)
}.all()
```

`filterVisible()` returns the concrete parent query, preserving fluent
composition. It does not affect root LOAD privacy, sibling edges, or nested
edges. Each nested edge opts in independently.

The existing behavior remains unchanged:

- a denied to-one target becomes `EdgeState.Loaded(null)`;
- denied to-many targets are omitted;
- no replacement rows are scanned beyond the selected per-parent window;
- query rejection and ordinary privacy, driver, or materialization exceptions
  remain failures;
- strict mode remains the default when the handle is ignored.

## Query Configuration Semantics

### One selection per edge

A query object may select each edge at most once.

```kotlin
client.users.query {
    loadPosts { where(Post.published eq true) }
    loadPosts { orderBy(Post.createdAt.desc()) } // rejected
}
```

EntKt must not silently replace the first block, merge two mutable target-query
objects, or make a retained handle govern whichever configuration happened to
be installed last. The second call throws an
`EntQueryConfigurationException` immediately, before any terminal or driver
I/O.

Callers compose all configuration for one edge in one block:

```kotlin
loadPosts {
    where(Post.published eq true)
    orderBy(Post.createdAt.desc())
}
```

This removes the current last-write-wins configuration and stale
`EagerLoad`-handle behavior.

Executing the same fully configured query object more than once is not a
duplicate selection. The selected graph remains part of that query until the
query object is discarded.

### Configuration failures

Add a dedicated public `EntQueryConfigurationException` for invalid query DSL
combinations. It is distinct from `EntQueryRejectedException`:

- configuration failures are deterministic application misuse discovered by
  the generated/query API;
- query rejection is a policy decision returned by a read interceptor.

Calls made while configuring a query, including a duplicate `load{Name}` or an
invalid traversal conversion, throw `EntQueryConfigurationException`
immediately.

A result-bearing terminal that discovers incompatible existing configuration
captures the same exception as `ReadResult.Failed`, following the canonical
result boundary. Its `getOrThrow()` projection throws that exact exception.

### Snapshot boundary

The public API builds a mutable query configuration. At terminal entry, the
framework captures the edge-load topology consumed by the executor. Mutating a
query after a terminal begins must not change that in-flight execution.

The executor RFC owns the more precise timing for defensive snapshots,
interceptor execution, and nested target operands.

## Terminal Compatibility

### Entity-materializing terminals

`load{Name}` is meaningful on terminals that return entities and can populate
their `Edges` container:

```kotlin
query.all()
query.firstOrNull()
```

All standard result projections of those terminals preserve the same selected
graph. For example, `getOrThrow()` changes failure projection, not loading.

An entity terminal with no selected edges behaves as it does today: returned
entities carry `EdgeState.Unloaded` for every relationship.

### Raw aggregate and existence terminals

Raw count, existence, and aggregate terminals do not return entities and
cannot expose loaded edges. They must not silently ignore a selected graph:

```kotlin
val query = client.users.query {
    loadPosts()
}

query.rawCount() // ReadResult.Failed(EntQueryConfigurationException)
```

The same rule applies to every non-entity terminal on that query, including
grouped aggregates. Failure occurs before interceptor or driver execution.

Explain variants for incompatible terminals throw the same configuration
exception before driver explain work. The error message names the terminal and
the selected edge declarations.

This RFC does not prevent a future type-staged API from making these methods
unavailable at compile time. Until such a surface exists, explicit validation
is preferable to ignored configuration.

### Traversal conversion

`query{Name}` returns a fresh query rooted at the target entity. Carrying a
source query's selected result graph into that target query would have no
coherent meaning, while silently discarding it is surprising.

Therefore traversal rejects a source query with any selected edge:

```kotlin
client.users.query {
    loadProfile()
}.queryAuthoredPosts() // throws EntQueryConfigurationException
```

Callers either traverse first and then select the target graph:

```kotlin
client.users
    .query { where(User.active eq true) }
    .queryAuthoredPosts {
        loadComments()
    }
    .all()
```

or materialize the source graph with an entity terminal.

The error is thrown by `query{Name}` itself because traversal is a query
configuration operation, not a result-bearing terminal.

### Explain

Entity explain methods include the complete selected edge topology and use the
same declaration-derived path names as execution:

```text
User.authoredPosts.comments
```

Explain describes logical edge-load steps and the executor's current physical
plan. It must not turn `load{Name}` into a stable statement-count promise. The
executor and observability RFCs own query-count ranges, chunking, and rendered
driver details.

## Result Contract

The generated entity shape does not change:

```kotlin
data class User(
    val id: UUID,
    val edges: Edges = Edges(),
) {
    data class Edges(
        val posts: EdgeState<List<Post>> = EdgeState.Unloaded,
    )
}
```

For a successful entity terminal:

- `EdgeState.Unloaded` means the edge was not selected with `load{Name}`;
- `EdgeState.Loaded(null)` means a selected to-one edge produced no visible
  target;
- `EdgeState.Loaded(emptyList())` means a selected to-many edge produced no
  visible targets;
- `EdgeState.Loaded(value)` contains the materialized relationship.

No call to an `EdgeState` accessor issues database I/O. This RFC does not add
lazy loading.

Root entity order, root cardinality, per-parent edge ordering and bounds,
deduplication, privacy, and attachment semantics remain owned by the executor
RFC and are unchanged by the public rename.

## Privacy And Interceptors

Renaming the selection method does not weaken or move authorization:

- root LOAD privacy remains strict;
- selected edge LOAD privacy remains strict unless that exact `EdgeLoad`
  handle calls `filterVisible()`;
- one root-captured `PrivacyContext` flows through the logical operation;
- nested denial paths use declaration-derived edge names;
- an empty privacy batch invokes no privacy rule;
- user callbacks may deliberately issue their own database queries.

Read interceptors continue to receive their documented root, traversal, and
edge-load operations. `load{Name}` is a public graph-selection spelling, not a
new interceptor operation or an execution-order commitment.

## Execution Boundary

Every `load{Name}` call tells the caller that EntKt will perform relationship
materialization work in addition to selecting root rows. It intentionally does
not expose that work as a SQL strategy selector.

Under the accepted set-based executor:

- root rows are selected first;
- configured edges execute set-at-a-time and depth-first;
- nested edge paths do not execute once per populated parent group;
- many-to-many loading may require a junction read plus target reads;
- large parent sets may require driver-owned chunks;
- per-parent windows may use native storage support or a documented fallback.

Consequently, `loadPosts()` does not mean “one post query,” and several
`load{Name}` calls do not create a reliable arithmetic formula for physical
statement count. The API exposes the requested relationship graph. Explain and
observability surfaces expose the chosen execution plan.

Future executors may optimize the same graph differently if they preserve the
result, privacy, interceptor, ordering, failure, and explain contracts owned by
their RFCs. Such an optimization does not require renaming `load{Name}`.

## Generated Member Collisions

Codegen reserves every `load{Name}` method before source emission. Validation
must reject collisions involving:

- two edge declarations with the same generated stem;
- `load{Name}` versus a fixed query member;
- `load{Name}` versus another generated traversal/loading member;
- inherited or mixin edge declarations that resolve to the same method;
- names that collide after the naming RFC's first-character title-case step.

Diagnostics identify the schema class, delegated declaration name, generated
method, and storage edge name. Storage-name uniqueness alone is not sufficient
because storage strings do not name this API.

The internal executor helper currently named `loadEdges` is not part of the
application surface. Its implementation name may remain internal or change;
it must not collide with a generated public `load{Name}` member.

## Kotlin And JVM Surface

The Kotlin receiver DSL is normative:

```kotlin
loadPosts { where(Post.published eq true) }
```

Codegen must also emit a JVM-callable zero-block overload so Java callers do
not need to manufacture Kotlin's default-argument marker merely to request an
unfiltered edge. Java compile tests pin the actual generated signatures.

This RFC does not introduce a second Java-only method name or a Java-only edge
configuration abstraction. Any later general Java DSL improvement should
apply consistently to generated query blocks rather than special-case edge
loading.

## Compatibility And Migration

This is an intentional source-breaking rename. EntKt is greenfield, so the
generator removes the old family rather than retaining permanent aliases:

```text
withPosts      -> loadPosts
withAuthor     -> loadAuthor
EagerLoad<Q>   -> EdgeLoad<Q>
```

Migration is mechanical:

```diff
 client.users.query {
-    withPosts {
+    loadPosts {
         where(Post.published eq true)
-        withComments()
+        loadComments()
     }.filterVisible()
 }.all()
```

There is no behavior fallback under the old name. Generated collision
validation runs against only the new canonical surface.

The migration must also correct existing storage-derived edge method names to
declaration-derived names when the schema naming RFC lands. Those two changes
should ship together so application code migrates once.

## Implementation Direction

### Phase 1: Schema and codegen model

1. Carry each delegated edge declaration name into resolved query metadata.
2. Replace `withMethodName` with `loadMethodName` derived from the generated
   stem.
3. Reserve the complete `load{Name}` family in generated-member validation.
4. Report declaration, generated, and storage names distinctly in collision
   diagnostics.

### Phase 2: Generated query surface

1. Emit `load{Name}` target-query receiver methods.
2. Replace `EagerLoad` with `EdgeLoad` and preserve `filterVisible()`.
3. Track selected edges explicitly and reject duplicate selection.
4. Add the edge-load-plan snapshot consumed by the executor.
5. Validate terminal and traversal compatibility before I/O.

### Phase 3: Executor handoff

1. Make the generated query produce the immutable topology required by the
   set-based executor RFC.
2. Keep application edge names in privacy-denial paths, interceptor context,
   explain output, and diagnostics.
3. Remove the public API's dependency on per-edge generated execution bodies
   where the executor RFC replaces them.

### Phase 4: Migration and documentation

1. Replace every `with{Name}` example and test with `load{Name}`.
2. Rename `EagerLoad` documentation and compile tests to `EdgeLoad`.
3. Add a breaking-change entry describing the source migration.
4. Update query, privacy, lifecycle, codegen, and runtime documentation.

The public cutover should be atomic. Do not expose a release in which some
edges generate `with{Name}` while others generate `load{Name}`.

## Test Requirements

### Generated API

- zero-block and receiver-block `load{Name}` calls compile;
- to-one, to-many, many-to-many, inverse, self, and through-entity edges use
  the same method family;
- nested and sibling selections compile with target-specific IDE-visible
  receiver types;
- generated output contains no public `with{Name}` method;
- `EdgeLoad<ConcreteParentQuery>` and `filterVisible()` compile in Kotlin and
  Java-facing tests;
- divergent declaration and storage names generate only the declaration-based
  method;
- no pluralizer or target-type rule influences the method name.

### Configuration

- selecting one edge twice throws `EntQueryConfigurationException` before
  interceptor or driver work;
- the diagnostic names both selections and the declaration-derived edge path;
- a retained `EdgeLoad` handle cannot target another edge;
- executing one configured query more than once remains valid;
- terminal-entry plan capture is isolated from later query mutation.

### Terminals and traversal

- `all()` and `firstOrNull()` populate exactly the selected edges;
- unselected edges remain `EdgeState.Unloaded`;
- raw count, existence, ungrouped aggregate, and grouped aggregate terminals
  fail before I/O when a graph is configured;
- their result-bearing boundaries preserve the configuration exception;
- incompatible explain methods and `query{Name}` traversal fail explicitly;
- traversing first and selecting loads on the target query succeeds.

### Privacy and result semantics

- strict and `filterVisible()` behavior is identical before and after the
  rename;
- root denial and eager denial remain distinct;
- filter posture is edge-local and is not inherited;
- `Unloaded`, nullable loaded, empty loaded, and populated loaded states remain
  distinguishable;
- privacy-denial and explain paths use declaration names.

### Integration with execution

- the same public graph produces the immutable topology expected by the
  set-based executor;
- nested loading does not reintroduce per-parent query multiplication;
- M2M, chunking, per-parent windows, callback ordering, and failure precedence
  satisfy the executor RFC;
- call order does not accidentally override the executor's documented
  deterministic order.

### Collisions and migration

- generated-member validation catches every `load{Name}` collision before
  source emission;
- collision diagnostics distinguish application and storage identities;
- representative application, example, and documentation snippets compile
  without the old names;
- repository search finds no generated or documented public `with{Name}` and
  no public `EagerLoad` after the atomic cutover.

## Documentation Requirements

Public query documentation must state:

- relationships are unloaded unless selected with `load{Name}`;
- `{Name}` is the delegated schema edge property;
- loading preserves the root result while `query{Name}` changes the result
  root;
- nested target configuration uses the ordinary generated query DSL;
- loading is explicit but physical statement count is executor- and
  driver-dependent;
- `filterVisible()` is edge-local and strict privacy is the default;
- raw/non-entity terminals reject selected graphs rather than ignoring them;
- no relationship accessor performs implicit database I/O.

Examples should include:

- one zero-configuration edge;
- a filtered and ordered to-many edge;
- a nested graph;
- two edges to the same target type with different declaration names;
- a declaration/storage-name mismatch;
- strict loading and `filterVisible()`.

## Resolved Decisions

1. The generated public method is `load{Name}`, not `with{Name}`.
2. `{Name}` comes exclusively from the delegated edge declaration.
3. There is no `loadEdge { ... }` or `loadEdges { ... }` wrapper.
4. Edge query operations stay in the target query receiver block, not method
   arguments.
5. The same method family covers every relationship cardinality.
6. `query{Name}` traverses and changes the result root; `load{Name}` preserves
   the root and populates `EdgeState`.
7. The returned public handle is `EdgeLoad<ParentQuery>`.
8. Strict eager LOAD privacy remains the default; `filterVisible()` remains an
   explicit edge-local opt-in.
9. Selecting the same edge twice is an error, not last-write-wins or merge.
10. Non-entity terminals and source traversal never silently discard selected
    loads.
11. `load{Name}` signals relationship materialization, not a fixed SQL strategy
    or statement count.
12. Execution remains owned by the set-based executor RFC.
13. Old `with{Name}` and `EagerLoad` aliases are not retained.

## Related RFCs

- [Schema Declaration Names As Generated API](../schema/schema-declaration-api-names.md)
- [Set-Based Eager Graph Loader](set-based-eager-graph-loader.md)
- [Explicit Query Authority And Cost](explicit-query-authority-and-cost.md)
- [Query Observability Diagnostics](query-observability-diagnostics.md)
- [Loaded Edge State](../../implemented-features/query/loaded-edge-state.md)
- [Eager-Edge Privacy](../../implemented-features/api/operation-result-algebra.md#eager-edge-privacy)
