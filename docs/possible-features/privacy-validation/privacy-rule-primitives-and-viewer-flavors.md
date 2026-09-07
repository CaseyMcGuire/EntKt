# RFC: Privacy Rule Primitives And Viewer Flavors

## Status

Possible future feature. This is not implemented.

## Summary

Add ergonomic privacy-rule primitives inspired by Ent Framework's
privacy model:

- named rule constructors for common allow / require / deny shapes
- reusable FK and graph-reachability predicates
- viewer flavors / capabilities on `ViewerContext`
- stable rule names that feed privacy-denial messages and future explain
  output

This should not change EntKt's existing enforcement model. All CRUD
operations are fail-closed allow-lists — an explicit `Allow` is required to
pass — and these helpers just compose those decisions ergonomically.

## Motivation

EntKt already has the important safety properties:

- LOAD privacy checks hydrated rows and returns a failed read on denial
- singular `visibleOrNull()` and eager `filterVisible()` make privacy projection explicit
- write privacy sees `WriteCandidate`, update patches, and edge changes
- read-path filters such as soft delete live in query interceptors

The remaining problem is ergonomics. Application policies today tend to
be hand-written lambdas:

```kotlin
ArticleCreatePrivacyRule { context, candidate ->
    val viewerId = context.viewerContext.userIdOrNull()
        ?: return@ArticleCreatePrivacyRule PrivacyDecision.Deny("login required")
    if (candidate.authorId == viewerId) PrivacyDecision.Allow
    else PrivacyDecision.Deny("authorId must be current viewer")
}
```

That works, but it has three drawbacks:

- repeated boilerplate obscures the actual authorization rule
- anonymous lambdas make privacy failures and future explain output hard
  to read
- FK reference authorization is easy to forget on create / update paths

Ent Framework's useful idea is not its exact API, but the vocabulary:
`AllowIf`, `Require`, `DenyIf`, graph-reachability predicates such as
"can read this outgoing edge", and viewer flavors for request-scoped
capabilities such as admin or archive access.

## Non-Goals

- Do not change EntKt's default LOAD or write privacy semantics.
- Do not make graph-reachability access implicit.
- Do not make privacy rules silently filter normal reads.
- Do not push arbitrary privacy predicates into SQL.
- Do not replace query interceptors for query-shape filters such as
  tenant scope or soft delete.
- Do not implement edge-derived LOAD privacy in this RFC; that remains a
  separate explicit feature.
- Do not require applications to use the built-in rule helpers.

## Current Semantics To Preserve

Privacy is **fail-closed**: all four CRUD operations are allow-list based —
an explicit `Allow` is required, and falling off the end (or having no
rules / no policy) denies.

### LOAD

- `PrivacyDecision.Allow` stops evaluation and permits the load
- `PrivacyDecision.Deny(reason)` stops evaluation and rejects
- `PrivacyDecision.Continue` moves to the next rule
- falling off the end (or having no rules) denies

### CREATE / UPDATE / DELETE

Same allow-list semantics as LOAD (this changed from the earlier
write-deny-list model when CRUD privacy became fail-closed):

- `PrivacyDecision.Allow` stops evaluation and permits the write
- `PrivacyDecision.Deny(reason)` stops evaluation and rejects
- `PrivacyDecision.Continue` moves to the next rule
- falling off the end (or having no rules) denies

This RFC adds helpers that produce those same decisions. It does not
change the evaluator.

## Proposed API

### Named Rules

Add an optional name contract and runtime wrappers around the existing
`PrivacyRule<Client, Item>` / `BatchPrivacyRule<Client, Item>` interfaces.
`Client` is bounded by `EntRuleClient`; callbacks receive shared
`PrivacyRuleContext<Client>` and separate per-item state. LOAD items are the
entities themselves; CREATE items are write candidates.

Illustrative scalar constructor signatures:

```kotlin
fun <Client : EntRuleClient, Item> allowIf(
    name: String,
    predicate: (PrivacyRuleContext<Client>, Item) -> Boolean,
): PrivacyRule<Client, Item>

fun <Client : EntRuleClient, Item> denyIf(
    name: String,
    reason: String = name,
    predicate: (PrivacyRuleContext<Client>, Item) -> Boolean,
): PrivacyRule<Client, Item>

fun <Client : EntRuleClient, Item> require(
    name: String,
    reason: String = name,
    predicate: (PrivacyRuleContext<Client>, Item) -> Boolean,
): PrivacyRule<Client, Item>
```

The helper names are proposed. Preserve the existing decision algebra:

```text
allowIf: true -> Allow, false -> Continue
denyIf:  true -> Deny(reason), false -> Continue
require: true -> Continue, false -> Deny(reason)
```

A successful `require` does not authorize an operation. Every CRUD policy still
needs an explicit `Allow`; falling off the list denies. Rule order matters:
an early `Allow` skips later requirements, so policies needing every requirement
must place them first.

For example, a proposed helper could express a complete author policy:

```kotlin
privacy {
    create(
        require("viewer is authenticated") { context, _ ->
            context.viewerContext.viewer is Viewer.User
        },
        allowIf("authorId points to viewer") { context, candidate ->
            context.viewerContext.userIdOrNull() == candidate.authorId
        },
    )
}
```

Scalar helpers must retain the existing batch adaptation and rule order.
Wrapping an explicit batch rule to add a name must preserve its single batch
invocation and correlated decisions instead of turning it into per-item calls.

### Implemented Viewer Helpers

`Viewer` and `ViewerContext` already expose `userOrNull`, `userIdOrNull`, and
typed ID helpers. Reuse those functions; they are no longer proposed work.
Application-defined flavors and named rule constructors remain open.

### Viewer Flavors

Add application-defined capabilities to `ViewerContext`:

```kotlin
interface PrivacyFlavor

data class ViewerContext(
    val viewer: Viewer,
    val flavors: List<PrivacyFlavor> = emptyList(),
)

inline fun <reified F : PrivacyFlavor> ViewerContext.flavor(): F?
inline fun <reified F : PrivacyFlavor> ViewerContext.hasFlavor(): Boolean
```

Example:

```kotlin
data object AdminFlavor : PrivacyFlavor
data object ReadArchiveFlavor : PrivacyFlavor

privacy {
    load(
        allowIf("admin") { ctx, item -> ctx.viewerContext.hasFlavor<AdminFlavor>() },
        allowIf("published") { ctx, item -> item.published },
    )
}
```

`Viewer.PrivacyBypass` remains a framework-level bypass. Flavors are for
application-level capabilities that should still pass through explicit
policy rules and appear in rule traces.

Flavors should extend the explicit operation context rather than reintroduce
viewer-bound clients. A possible helper creates a new immutable context:

```kotlin
val adminContext = viewerContext.plusFlavor(AdminFlavor)
client.posts.query().all(adminContext).getOrThrow()
```

`plusFlavor`, `flavor`, and `hasFlavor` are proposed; existing `ViewerContext`
currently contains only the viewer. Propagate the resulting context through
nested rule reads explicitly and preserve the same context instance within an
operation. Exact storage and helper names remain open.

## FK And Graph Predicates

Add reusable predicates for common relationship authorization patterns.
The first version should favor generated, entity-specific helpers over
reflection-heavy generic helpers.

### FK Points To Viewer

Generated helper examples:

```kotlin
object ArticlePrivacy {
    fun allowAuthor(): ArticleLoadPrivacyRule =
        allowIf("Article.authorId points to viewer") { ctx, item ->
            ctx.viewerContext.userIdOrNull() == item.authorId
        }

    fun requireAuthorIsViewer(): ArticleCreatePrivacyRule =
        require("Article.authorId points to viewer") { ctx, item ->
            ctx.viewerContext.userIdOrNull() == item.authorId
        }
}
```

Generated helpers should be based on `belongsTo` / FK metadata, not field
name guessing. Nullable FKs return `false` when null.

### Can Read Outgoing Edge

For create / update, the common rule is: "the viewer may reference this
FK target if they can read the target entity."

Example generated helper:

```kotlin
object CommentPrivacy {
    fun requireCanReadTopic(): CommentCreatePrivacyRule =
        require("viewer can read Comment.topic") { ctx, item ->
            ctx.client.topics.findById(ctx.viewerContext, item.topicId)
                .visibleOrNull().getOrThrow() != null
        }
}
```

Open implementation detail: this helper must avoid leaking whether the
target row exists but is unreadable. The current `visibleOrNull()` result
projection maps only root LOAD denial to absence. Operational failures must
remain failures, not be caught and treated as an unreadable target.

### Can Update / Delete Outgoing Edge

Some policies need stronger reference permissions:

```kotlin
fun requireCanUpdateTopic(): CommentUpdatePrivacyRule
fun requireCanDeleteTopic(): CommentDeletePrivacyRule
```

These delegate to the target entity's update or delete privacy by loading
the target and evaluating the appropriate target policy. This should be a
later phase unless there is a concrete use case; read permission on FK
targets is the most common write-reference check.

### Incoming Edge From Viewer Exists

For membership-style access, add a helper that checks for a junction row
connecting the viewer to the current entity:

```kotlin
object OrganizationPrivacy {
    fun allowIfViewerMembershipExists(): OrganizationLoadPrivacyRule =
        allowIf("viewer has organization membership") { ctx, item ->
            val viewerId = ctx.viewerContext.userIdOrNull() ?: return@allowIf false
            ctx.client.employments.query()
                .where(Employment.userId eq viewerId)
                .where(Employment.organizationId eq item.id)
                .firstOrNull(ctx.viewerContext)
                .visibleOrNull().getOrThrow() != null
        }
}
```

This sketch checks the selected membership row. If the intended rule should
find any readable row after an earlier denied row, its scanning or predicate
visibility contract must be designed explicitly. No root `visibleExists`
terminal exists today. Use generated metadata for relationship linkage.

## Generated Helper Placement

Generate one optional helper object per entity:

```kotlin
object ArticlePrivacy {
    fun allowIfAuthorIdPointsToViewer(): ArticleLoadPrivacyRule
    fun requireAuthorIdPointsToViewerOnCreate(): ArticleCreatePrivacyRule
    fun requireAuthorIdPointsToViewerOnUpdate(): ArticleUpdatePrivacyRule
    fun requireCanReadAuthorOnCreate(): ArticleCreatePrivacyRule
    fun requireCanReadAuthorOnUpdate(): ArticleUpdatePrivacyRule
}
```

Naming should prefer the relationship declaration name when available:

```kotlin
ArticlePrivacy.requireCanReadAuthorOnCreate()
```

not:

```kotlin
ArticlePrivacy.requireCanReadAuthorIdOnCreate()
```

This depends on the implemented field-backed FK declaration-name work.

## Diagnostics

Named rules should improve failures immediately:

```text
CREATE denied on Article by rule "authorId points to viewer": authorId points to viewer
```

The shared runtime evaluator could use:

```kotlin
val ruleName = (rule as? NamedPrivacyRule)?.ruleName
    ?: rule::class.qualifiedName
    ?: rule.toString()
```

This should also become the rule identifier consumed by the existing
possible future [Privacy / Validation Explain Mode](privacy-validation-explain-mode.md).

## Relationship To Existing Features

- [Read-Path Interceptors](../../implemented-features/query/read-path-interceptors.md) remain the
  right home for query-shape filters. These helpers do not replace
  interceptors.
- [Soft Delete](../../implemented-features/schema/soft-delete.md) should
  continue to be a mixin plus interceptor, not a privacy-rule primitive.
- [Edge-Derived LOAD Privacy](edge-derived-load-privacy.md) remains a
  separate feature for allowing reads based on eager-load source context.
- [Privacy / Validation Explain Mode](privacy-validation-explain-mode.md)
  should reuse rule names introduced here.
- [Policy Test Helpers](policy-test-helpers.md) should expose these
  primitives naturally in generated harnesses.

## Implementation Plan

1. Add runtime named-rule helpers and `NamedPrivacyRule`.
2. Add proposed `PrivacyFlavor` support and immutable context helpers.
3. Update shared runtime evaluators to include rule names in denial messages
   and trace hooks; generated code supplies only schema-specific adapters.
4. Generate per-entity FK/viewer helper rules from relationship metadata.
5. Generate or document graph-predicate helpers for can-read outgoing FK
   and incoming membership existence.
6. Update docs and examples to prefer named helpers for common policies.

## Test Requirements

Before implementation, add tests for:

- `allowIf` returns `Allow` on true and `Continue` on false
- `denyIf` returns `Deny` on true and `Continue` on false
- `require` returns `Continue` on true and `Deny` on false
- `require` composes correctly under fail-closed (allow-list) write semantics
- `require` alone does not accidentally allow LOAD privacy
- named rules surface their names in privacy denial messages
- anonymous rules still work and use a fallback name
- `ViewerContext` remains source-compatible with existing construction
  through a default `flavors = emptyList()` parameter
- `hasFlavor<T>()` and `flavor<T>()` work for application-defined flavors
- `Viewer.PrivacyBypass` continues to bypass all privacy checks
- generated "FK points to viewer" helpers handle required and nullable FKs
- generated "can read outgoing edge" helpers return false for missing,
  null, or unreadable targets
- incoming-edge membership helpers do not grant access when the junction
  row is absent or not visible

## Open Questions

- Should graph predicates live in generated `{Entity}Privacy` helper
  objects only, or should runtime expose generic building blocks too?
- Should `PrivacyFlavor` instances be stored as a list, a map by class, or
  a typed capability set that supports multiple instances of the same
  flavor type?
- Should rule names be included in public exception messages by default,
  or only in structured fields / explain output?
- Should `requireCanReadOutgoingEdge` collapse target `NotFound` and
  target privacy denial into the same denial reason to avoid existence
  leaks?
