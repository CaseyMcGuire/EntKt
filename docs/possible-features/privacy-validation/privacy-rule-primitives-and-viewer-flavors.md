# RFC: Privacy Rule Primitives And Viewer Flavors

## Status

Possible future feature. This is not implemented.

## Summary

Add ergonomic privacy-rule primitives inspired by Ent Framework's
privacy model:

- named rule constructors for common allow / require / deny shapes
- reusable FK and graph-reachability predicates
- viewer flavors / capabilities on `PrivacyContext`
- stable rule names that feed privacy-denial messages and future explain
  output

This should not change EntKt's existing enforcement model. All CRUD
operations are fail-closed allow-lists — an explicit `Allow` is required to
pass — and these helpers just compose those decisions ergonomically.

## Motivation

EntKt already has the important safety properties:

- LOAD privacy rechecks hydrated rows and throws on denied rows by default
- `visible*` APIs are explicit when callers want privacy filtering
- write privacy sees `WriteCandidate`, update patches, and edge changes
- read-path filters such as soft delete live in query interceptors

The remaining problem is ergonomics. Application policies today tend to
be hand-written lambdas:

```kotlin
ArticleCreatePrivacyRule { ctx ->
    val viewer = ctx.privacy.viewer as? Viewer.User
        ?: return@ArticleCreatePrivacyRule PrivacyDecision.Deny("login required")
    if (ctx.candidate.authorId == viewer.id) PrivacyDecision.Continue
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

Add a lightweight named-rule wrapper:

```kotlin
interface NamedPrivacyRule {
    val ruleName: String
}

class NamedRule<C>(
    override val ruleName: String,
    private val delegate: PrivacyRule<C>,
) : PrivacyRule<C>, NamedPrivacyRule {
    override fun run(ctx: C): PrivacyDecision = delegate.run(ctx)
}
```

Add runtime helper constructors:

```kotlin
fun <C> allowIf(
    name: String,
    predicate: (C) -> Boolean,
): PrivacyRule<C>

fun <C> denyIf(
    name: String,
    reason: String = name,
    predicate: (C) -> Boolean,
): PrivacyRule<C>

fun <C> require(
    name: String,
    reason: String = name,
    predicate: (C) -> Boolean,
): PrivacyRule<C>
```

Decision behavior:

```text
allowIf: true -> Allow, false -> Continue
denyIf:  true -> Deny(reason), false -> Continue
require: true -> Continue, false -> Deny(reason)
```

`require(...)` is intended for write privacy and validation-style
authorization. It composes as an AND because successful rules continue
and failed rules deny. In LOAD privacy, `require(...)` by itself does
not allow access because LOAD end-of-list still denies.

Rule ordering still matters. `PrivacyDecision.Allow` short-circuits
write privacy today, and this RFC preserves that behavior. A broad
`allowIf("admin")` before a `require(...)` rule intentionally bypasses
later checks; applications that want every requirement enforced should
put `require(...)` rules first or avoid early `Allow` rules in that
policy.

Example:

```kotlin
privacy {
    load(
        allowIf("article is published") { ctx ->
            ctx.entity.published
        },
        allowIf("viewer is article author") { ctx ->
            ctx.privacy.viewer.userIdOrNull() == ctx.entity.authorId
        },
    )

    create(
        require("viewer is authenticated") { ctx ->
            ctx.privacy.viewer is Viewer.User
        },
        require("authorId points to viewer") { ctx ->
            ctx.privacy.viewer.userIdOrNull() == ctx.candidate.authorId
        },
    )
}
```

### Viewer Helpers

Add small helper functions for common viewer checks:

```kotlin
fun Viewer.userIdOrNull(): Any? =
    (this as? Viewer.User)?.id

fun PrivacyContext.userIdOrNull(): Any? =
    viewer.userIdOrNull()
```

These helpers keep policies readable without changing the existing
`Viewer` sealed interface.

### Viewer Flavors

Add application-defined capabilities to `PrivacyContext`:

```kotlin
interface PrivacyFlavor

data class PrivacyContext(
    val viewer: Viewer,
    val flavors: List<PrivacyFlavor> = emptyList(),
)

inline fun <reified F : PrivacyFlavor> PrivacyContext.flavor(): F?
inline fun <reified F : PrivacyFlavor> PrivacyContext.hasFlavor(): Boolean
```

Example:

```kotlin
data object AdminFlavor : PrivacyFlavor
data object ReadArchiveFlavor : PrivacyFlavor

privacy {
    load(
        allowIf("admin") { ctx -> ctx.privacy.hasFlavor<AdminFlavor>() },
        allowIf("published") { ctx -> ctx.entity.published },
    )
}
```

`Viewer.PrivacyBypass` remains a framework-level bypass. Flavors are for
application-level capabilities that should still pass through explicit
policy rules and appear in rule traces.

Scoped clients can add flavors without replacing the viewer:

```kotlin
client.withPrivacyFlavor(AdminFlavor) { adminClient ->
    adminClient.posts.query().allOrThrow()
}
```

This is equivalent to:

```kotlin
client.withPrivacyContext(
    client.currentPrivacyContext().plusFlavor(AdminFlavor),
) { adminClient -> ... }
```

The exact helper names can be finalized during implementation.

## FK And Graph Predicates

Add reusable predicates for common relationship authorization patterns.
The first version should favor generated, entity-specific helpers over
reflection-heavy generic helpers.

### FK Points To Viewer

Generated helper examples:

```kotlin
object ArticlePrivacy {
    fun allowAuthor(): ArticleLoadPrivacyRule =
        allowIf("Article.authorId points to viewer") { ctx ->
            ctx.privacy.userIdOrNull() == ctx.entity.authorId
        }

    fun requireAuthorIsViewer(): ArticleCreatePrivacyRule =
        require("Article.authorId points to viewer") { ctx ->
            ctx.privacy.userIdOrNull() == ctx.candidate.authorId
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
        require("viewer can read Comment.topic") { ctx ->
            ctx.client.topics.visibleByIdOrNull(ctx.candidate.topicId) != null
        }
}
```

Open implementation detail: this helper must avoid leaking whether the
target row exists but is unreadable. `visibleByIdOrNull` has the right
high-level shape because it collapses missing and denied rows to null.
If the implementation needs stricter error handling, it can catch
`PrivacyDeniedException` internally and return false.

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
        allowIf("viewer has organization membership") { ctx ->
            val viewerId = ctx.privacy.userIdOrNull() ?: return@allowIf false
            ctx.client.employments.query()
                .where(Employment.userId eq viewerId)
                .where(Employment.organizationId eq ctx.entity.id)
                .visibleExists()
        }
}
```

This should use generated metadata where possible so users do not have to
hand-write the junction query every time.

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

The generated evaluator can use:

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
2. Add `PrivacyFlavor` support to `PrivacyContext`, plus scoped-client
   helper APIs for adding flavors.
3. Update generated privacy evaluators to include rule names in denial
   messages and trace hooks.
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
- `PrivacyContext` remains source-compatible with existing construction
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
