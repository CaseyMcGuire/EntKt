# RFC: Edge Traversal Query Blocks

## Status

Implemented (2026-07-12).

As built, exactly per the proposed direction: all generated traversal
methods — paired `hasMany` / `hasOne` / `belongsTo` and many-to-many —
take `block: TargetQuery.() -> Unit = {}` and return
`target.apply(block)`. The block runs after traversal seeding and after
the source-query snapshot, so it configures the target query only and
cannot leak into the bridge predicate. No traversal lowering, terminal,
eager-loading, privacy, or interceptor behavior changed.

Coverage: `EdgeCodegenTest` asserts the paired and M2M signatures;
`TraversalQueryBlockIntegrationTest` proves block/chaining equivalence,
block predicates on the target, M2M block support, snapshot-at-call
semantics under the block form, and unchanged traversal interceptor
context. Existing no-arg call sites across the test suite compile
unchanged. Guide: [Edge Traversal](../../04-queries.md#edge-traversal).

## Summary

Allow generated edge traversal methods to accept the same query receiver block
used by repository and index query helpers.

Today, generated traversals return a target query:

```kotlin
entClient.conversations.indexes.userId(userId).query()
    .queryConversationAssets()
    .where(ConversationAsset.assetId.eq(assetId))
    .orderBy(ConversationAsset.id.order(OrderDirection.DESC))
    .firstOrNull()
    ?.conversationId
```

This works, but it reads less cleanly than repository and index queries, where
the query shape can live inside the query call:

```kotlin
entClient.conversations.indexes
    .userId(userId)
    .query()
    .queryConversationAssets {
        where(ConversationAsset.assetId.eq(assetId))
        orderBy(ConversationAsset.id.order(OrderDirection.DESC))
    }
    .firstOrNull()
    ?.conversationId
```

The proposed change is small: generate traversal methods with a defaulted
target-query receiver block.

## Motivation

EntKT already uses query receiver blocks in two common places:

```kotlin
client.posts.query {
    where(Post.authorId.eq(authorId))
    orderBy(Post.id.desc())
}
```

```kotlin
client.posts.indexes.authorId(authorId).query {
    orderBy(Post.id.desc())
}
```

Edge traversal is the main query surface that does not follow that shape. That
makes otherwise idiomatic query chains spill target predicates and ordering into
the outer chain.

The difference is not a correctness bug, but it is a principle-of-least-surprise
issue: if `query { ... }` is the normal way to shape a generated query, then
`queryEdge { ... }` should work the same way.

## Non-Goals

- Do not change traversal lowering or SQL semantics.
- Do not add a new terminal method.
- Do not change eager-loading `withEdge { ... }` behavior.
- Do not alter privacy, read interceptors, or edge-predicate interceptor
  behavior.
- Do not introduce a separate edge-query builder type.

## Proposed API

Generate traversal methods as:

```kotlin
fun queryConversationAssets(
    block: ConversationAssetQuery.() -> Unit = {},
): ConversationAssetQuery
```

For many-to-many edges, generate the same shape:

```kotlin
fun queryMembers(
    block: UserQuery.() -> Unit = {},
): UserQuery
```

Existing call sites remain source-compatible:

```kotlin
conversationQuery.queryConversationAssets()
```

New call sites can configure the target query inline:

```kotlin
conversationQuery.queryConversationAssets {
    where(ConversationAsset.assetId.eq(assetId))
    orderBy(ConversationAsset.id.order(OrderDirection.DESC))
    limit(1)
}
```

## Semantics

The block applies to the target query returned by the traversal method.

Conceptually:

```kotlin
fun queryConversationAssets(
    block: ConversationAssetQuery.() -> Unit = {},
): ConversationAssetQuery {
    val target = ConversationAssetQuery(driver, client)
    // existing traversal setup
    return target.apply(block)
}
```

The generated traversal setup still:

- creates the target query
- seeds traversal context for query diagnostics and interceptors
- snapshots the source query at traversal-call time
- installs the deferred source-step predicate

The block should not mutate the source query. It only configures the target
query, exactly like calling `.where(...)`, `.orderBy(...)`, `.limit(...)`, or
`.offset(...)` after the traversal returns.

## Compatibility

Kotlin source compatibility should be preserved because the block has a default
value:

```kotlin
queryConversationAssets()
```

continues to compile.

This project is Kotlin-first and generated-source-first, so JVM binary
compatibility for previously generated query classes is not a major constraint.
If Java interop becomes important, the generator could emit both:

```kotlin
fun queryConversationAssets(): ConversationAssetQuery
fun queryConversationAssets(block: ConversationAssetQuery.() -> Unit): ConversationAssetQuery
```

or use `@JvmOverloads`. That is probably unnecessary for V1 because repository
and index query helpers already use defaulted receiver blocks.

## Alternatives

### Keep Chaining Only

Do nothing and keep:

```kotlin
query.queryConversationAssets()
    .where(...)
    .orderBy(...)
```

This is explicit and already works, but it is inconsistent with other generated
query entry points.

### Require `apply`

Callers can write:

```kotlin
query.queryConversationAssets().apply {
    where(...)
    orderBy(...)
}
```

This avoids codegen changes, but it pushes boilerplate to every call site and
is less discoverable than matching `query { ... }`.

### Add A Different Method Name

Generate a second method:

```kotlin
queryConversationAssetsQuery {
    where(...)
}
```

This avoids changing the existing method signature, but adds another generated
name for the same concept. That is more surprising than a defaulted block on
the existing traversal method.

## Proposed Direction

Generate defaulted receiver-block parameters on all traversal methods:

- paired `hasMany` traversals
- paired `hasOne` / `belongsTo` traversals
- many-to-many traversals

Keep the implementation minimal:

- build the target query exactly as today
- install traversal metadata exactly as today
- return `target.apply(block)` instead of `target`

## Test Requirements

Before implementation, add or update tests for:

- generated paired traversal signature includes `block: TargetQuery.() -> Unit = {}`
- generated many-to-many traversal signature includes the same block shape
- existing no-arg traversal call sites still compile
- compile or behavioral coverage proves `queryTargets { where(...); orderBy(...) }`
  is equivalent to `queryTargets().where(...).orderBy(...)`
- block predicates are applied to the target query
- source-query predicates are still snapshotted at traversal-call time
- traversal read interceptors still see the same traversal context
