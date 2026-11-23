# RFC: Privacy Rule Primitives And Viewer Flavors

## Status

Possible future feature. This is not implemented.

## Summary

Add ergonomic privacy-rule primitives inspired by Ent Framework's
privacy model:

- named rule constructors for common allow / require / deny shapes
- reusable FK and graph-reachability predicates
- stable rule names that feed privacy-denial messages and future explain
  output

Viewer flavors were part of this proposal. The implemented
[application-defined viewer](../../06-privacy.md#viewer) replaces them: a
capability is a property of the application's own viewer type. See
[Viewer Capabilities](#viewer-capabilities).

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
    val viewerId = context.viewer.userId
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
`AllowIf`, `Require`, `DenyIf`, and graph-reachability predicates such as
"can read this outgoing edge".

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
`PrivacyRule<Viewer, Client, Item>` / `BatchPrivacyRule<Viewer, Client, Item>`
interfaces. `Viewer` is the application's viewer type and `Client` is bounded
by `EntRuleClient`; callbacks receive shared `PrivacyRuleContext<Viewer, Client>`
and separate per-item state. LOAD items are the entities themselves; CREATE
items are write candidates.

Illustrative scalar constructor signatures:

```kotlin
fun <Viewer : Any, Client : EntRuleClient, Item> allowIf(
    name: String,
    predicate: (PrivacyRuleContext<Viewer, Client>, Item) -> Boolean,
): PrivacyRule<Viewer, Client, Item>

fun <Viewer : Any, Client : EntRuleClient, Item> denyIf(
    name: String,
    reason: String = name,
    predicate: (PrivacyRuleContext<Viewer, Client>, Item) -> Boolean,
): PrivacyRule<Viewer, Client, Item>

fun <Viewer : Any, Client : EntRuleClient, Item> require(
    name: String,
    reason: String = name,
    predicate: (PrivacyRuleContext<Viewer, Client>, Item) -> Boolean,
): PrivacyRule<Viewer, Client, Item>
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
            context.viewer.userId != null
        },
        allowIf("authorId points to viewer") { context, candidate ->
            context.viewer.userId == candidate.authorId
        },
    )
}
```

Scalar helpers must retain the existing batch adaptation and rule order.
Wrapping an explicit batch rule to add a name must preserve its single batch
invocation and correlated decisions instead of turning it into per-item calls.

### Viewer Capabilities

This section used to propose viewer flavors: `flavors: List<PrivacyFlavor>` on
the runtime's context, read with `hasFlavor<T>()`. It also reused the runtime's
viewer ID helpers. Both are superseded by the implemented
[application-defined viewer](../../06-privacy.md#viewer), which removed the
runtime's `Viewer` type and its helpers.

A capability is now a property of the application's viewer type, checked by the
compiler instead of looked up by class at run time:

```kotlin
privacy {
    load(
        allowIf("admin") { ctx, _ -> Role.ADMIN in ctx.viewer.roles },
        allowIf("published") { _, item -> item.published },
    )
}
```

Elevating one operation builds a new context from a changed viewer. It still
passes through explicit policy rules and appears in rule traces:

```kotlin
val archiveContext = ViewerContext.AsViewer(viewer.copy(canReadArchived = true))
client.posts.query().all(archiveContext).getOrThrow()
```

`ViewerContext.PrivacyBypass` remains the framework-level bypass.

## FK And Graph Predicates

Add reusable predicates for common relationship authorization patterns.
The first version should favor generated, entity-specific helpers over
reflection-heavy generic helpers.

### FK Points To Viewer

Generated helper examples:

```kotlin
object ArticlePrivacy {
    fun allowAuthor(viewerId: (AppViewer) -> UUID?): ArticleLoadPrivacyRule =
        allowIf("Article.authorId points to viewer") { ctx, item ->
            val id = viewerId(ctx.viewer)
            id != null && id == item.authorId
        }

    fun requireAuthorIsViewer(viewerId: (AppViewer) -> UUID?): ArticleCreatePrivacyRule =
        require("Article.authorId points to viewer") { ctx, item ->
            val id = viewerId(ctx.viewer)
            id != null && id == item.authorId
        }
}
```

The runtime does not know which property of the application's viewer type holds
its ID, so a generated helper takes an accessor for it, such as
`ArticlePrivacy.allowAuthor(AppViewer::userId)`. The accessor's return type is
the FK's type, so a mismatch does not compile.

Generated helpers should be based on `belongsTo` / FK metadata, not field
name guessing. Nullable FKs return `false` when null, and so does a null viewer
ID: the two must never match as `null == null`.

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
            val viewerId = ctx.viewer.userId ?: return@allowIf false
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

- [Read-Path Interceptors](../../04-queries.md#read-path-interceptors) remain the
  right home for query-shape filters. These helpers do not replace
  interceptors.
- Soft Delete should
  continue to be a mixin plus interceptor, not a privacy-rule primitive.
- [Edge-Derived LOAD Privacy](edge-derived-load-privacy.md) remains a
  separate feature for allowing reads based on eager-load source context.
- [Privacy / Validation Explain Mode](privacy-validation-explain-mode.md)
  should reuse rule names introduced here.
- [Policy Test Helpers](policy-test-helpers.md) should expose these
  primitives naturally in generated harnesses.

## Implementation Plan

1. Add runtime named-rule helpers and `NamedPrivacyRule`.
2. Update shared runtime evaluators to include rule names in denial messages
   and trace hooks; generated code supplies only schema-specific adapters.
3. Generate per-entity FK/viewer helper rules from relationship metadata. Each
   takes an accessor for the viewer's ID.
4. Generate or document graph-predicate helpers for can-read outgoing FK
   and incoming membership existence.
5. Update docs and examples to prefer named helpers for common policies.

## Test Requirements

Before implementation, add tests for:

- `allowIf` returns `Allow` on true and `Continue` on false
- `denyIf` returns `Deny` on true and `Continue` on false
- `require` returns `Continue` on true and `Deny` on false
- `require` composes correctly under fail-closed (allow-list) write semantics
- `require` alone does not accidentally allow LOAD privacy
- named rules surface their names in privacy denial messages
- anonymous rules still work and use a fallback name
- `ViewerContext.PrivacyBypass` continues to bypass all privacy checks
- generated "FK points to viewer" helpers handle required and nullable FKs,
  and never match a null viewer ID with a null FK
- generated "can read outgoing edge" helpers return false for missing,
  null, or unreadable targets
- incoming-edge membership helpers do not grant access when the junction
  row is absent or not visible

## Open Questions

- Should graph predicates live in generated `{Entity}Privacy` helper
  objects only, or should runtime expose generic building blocks too?
- Should rule names be included in public exception messages by default,
  or only in structured fields / explain output?
- Should `requireCanReadOutgoingEdge` collapse target `NotFound` and
  target privacy denial into the same denial reason to avoid existence
  leaks?
