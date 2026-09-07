# RFC: Edge-Derived LOAD Privacy

## Status

Possible future feature. This is not implemented.

## Summary

Add an explicit privacy rule primitive that allows an entity to be read
when it is loaded through a specific edge from an already-readable parent
entity.

Example use case:

```kotlin
object PostPolicy : EntityPolicy<Post, PostPolicyScope> {
    override fun configure(scope: PostPolicyScope) = with(scope) {
        privacy {
            load(
                PostPrivacy.allowIfLoadedThroughUserPosts(),
                PostPrivacy.allowAuthor(),
            )
        }
    }
}
```

This would allow a `Post` to be read when it is returned as part of
`User.posts`, assuming the parent `User` has already passed LOAD privacy.

## Motivation

Some applications model authorization around graph navigation:

- if the viewer can read a parent entity, some child entities are safe to
  expose through a specific edge
- direct reads of the child entity may still require stricter rules
- list screens often want edge-local visibility semantics

Without an edge-aware rule, applications must duplicate parent checks
inside child policies or write custom eager-load queries.

## Non-Goals

- Do not make parent-derived access implicit for all edges.
- Do not add field-level privacy.
- Do not make direct `byId` or root query reads inherit edge context.
- Do not support traversal-query inheritance in the first version.
- Do not bypass normal LOAD privacy by default.

## Proposed API

The first version should be explicit and edge-scoped.

Generated rule helpers could look like:

```kotlin
object PostPrivacy {
    fun allowIfLoadedThroughUserPosts(): PostLoadPrivacyRule =
        AllowLoadViaEdge(
            parentEntity = "User",
            edgeName = "posts",
        )
}
```

Or, if generated constants exist for edges:

```kotlin
privacy {
    load(
        AllowLoadViaEdge(User.edges.posts),
        PostPrivacy.allowAuthor(),
    )
}
```

The important property is that the rule names the parent entity and edge.
There should not be a broad `AllowLoadViaAnyReadableParent` helper in the
initial version.

## Load Source Context

Generated LOAD privacy contexts would need to include why the entity is
being loaded.

```kotlin
sealed interface LoadSource {
    data object Direct : LoadSource

    data class Edge(
        val parentEntityName: String,
        val edgeName: String,
        val parent: Any,
    ) : LoadSource
}
```

Current LOAD callbacks receive `PrivacyRuleContext<ReadOnlyEntClient>` plus
the entity, and batch callbacks receive an ordered entity batch. There is no
per-entity `PostLoadPrivacyContext` to extend.

The `LoadSource` sketch above describes the information the rule needs. A
future implementation must decide how source metadata accompanies a LOAD batch
without restoring combined viewer/client/entity contexts or evaluating shared
targets once per parent. A shared target may have several readable source
parents in the same eager step; source attribution must preserve those
associations.

Root reads would carry direct-source information. Eager reads would carry the
relevant readable parent associations. The exact callback representation is
an open design decision, not an existing generated context type.

## Rule Semantics

An edge-derived rule should only allow when all of these are true:

- the load source is `LoadSource.Edge`
- the source parent entity name matches
- the source edge name matches
- the parent entity has already passed LOAD privacy in the current
  operation

If any condition is not met, the rule should return `PrivacyDecision.Continue`.

Example implementation shape:

```kotlin
class AllowLoadViaEdge<C>(
    private val parentEntityName: String,
    private val edgeName: String,
) : PrivacyRule<C> {
    override fun run(ctx: C): PrivacyDecision {
        val source = ctx.source as? LoadSource.Edge
            ?: return PrivacyDecision.Continue

        return if (
            source.parentEntityName == parentEntityName &&
            source.edgeName == edgeName
        ) {
            PrivacyDecision.Allow
        } else {
            PrivacyDecision.Continue
        }
    }
}
```

The actual implementation would need generated or bounded context types
so `ctx.source` is available in a type-safe way.

## Enforcement Points

This feature should apply only to eager-loaded edges in the first version:

```kotlin
client.users.query {
    loadPosts()
}.all(viewerContext).getOrThrow()
```

It should not apply to direct child reads:

```kotlin
client.posts.findById(viewerContext, postId)
client.posts.query().all(viewerContext).getOrThrow()
```

Traversal queries should remain direct child reads unless a later design
defines parent context preservation:

```kotlin
client.users.query {
    where(User.id eq userId)
}.queryPosts().all(viewerContext).getOrThrow()
```

## Safety Considerations

Parent readability does not always imply child readability. For example,
a readable `User` should not automatically expose every `Post` if drafts,
private posts, blocked-user content, or organization boundaries apply.

For that reason:

- the rule must be explicit
- the rule must name the edge
- docs should show it as a convenience for specific graph relationships,
  not as a blanket default
- direct reads should still require the child entity's normal LOAD rules

## Open Questions

- Should `LoadSource.Edge.parent` be typed, or is `Any` acceptable for the
  first implementation?
- Should generated contexts use a common `HasLoadSource` interface so
  generic built-in rules can access `source`?
- Should to-one and to-many eager loads both receive the same source
  shape?
- Should M2M eager loads include junction table metadata in `LoadSource`?
- Should traversal queries ever preserve parent context, or should this
  remain eager-load only?

## Test Requirements

Before implementation, add tests for:

- direct child read does not match edge-derived rule
- eager to-one child read can be allowed through a named readable parent
- eager to-many child read can be allowed through a named readable parent
- eager M2M child read can be allowed through a named readable parent
- wrong parent entity does not allow
- wrong edge name does not allow
- denied parent prevents child evaluation from being treated as
  parent-derived
- nested eager loads preserve the immediate parent source

