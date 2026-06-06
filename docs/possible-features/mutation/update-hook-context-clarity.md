# RFC: Update Hook Context Clarity

## Status

Possible future feature. This is not implemented.

## Summary

Simplify and clarify the generated `beforeUpdate` hook context naming.

The current context is powerful, but the terminology is dense:

```kotlin
ctx.before
ctx.patch
ctx.pendingEdges
ctx.mutation
```

Privacy and validation contexts use related but different names:

```kotlin
ctx.before
ctx.requestedPatch
ctx.effectivePatch
ctx.candidate
ctx.edgeChanges
```

This RFC proposes a clearer vocabulary and, where useful, compatibility
aliases so hook authors can reason about updates in the same terms used by
privacy and validation.

## Motivation

The update pipeline has several distinct concepts:

- the row as it existed before the update
- the caller/hook-requested scalar patch
- framework-added update defaults
- the final after-state candidate
- pending M2M edge intent
- computed M2M database deltas
- a restricted mutable view hooks can write through

Those concepts are valid. The clunkiness comes from inconsistent naming and
from hook contexts exposing some phases while privacy/validation contexts expose
others.

The API should teach a simple mental model:

```text
before row + requested changes + framework defaults = final write
```

## Goals

- Use one vocabulary across hooks, privacy, and validation.
- Make the distinction between caller intent and final write explicit.
- Keep hook mutation through a restricted writable view.
- Avoid removing useful data from hooks without a replacement.
- Preserve source compatibility if possible through aliases.

## Non-Goals

- Do not change update execution order in this RFC.
- Do not make hooks responsible for authorization or invariants.
- Do not expose public save/reentrant mutation methods inside hook contexts.
- Do not change M2M mutation semantics.

## Proposed Vocabulary

Prefer these names:

| Current | Proposed | Meaning |
|---|---|---|
| `before` | `before` | Loaded current owner row |
| `patch` | `requestedPatch` | Caller plus prior-hook scalar/FK intent at hook entry |
| `pendingEdges` | `requestedEdges` | Caller-requested link-table M2M intent |
| `mutation` | `changes` or `mutableChanges` | Restricted writable view for this hook |
| `edgeChanges` | `edgeChanges` | Computed M2M database delta, available after diffing |
| `effectivePatch` | `effectivePatch` | Requested patch after update defaults |
| `candidate` | `candidate` or `afterCandidate` | Full after-state write candidate |

For hook contexts, the first version could generate aliases:

```kotlin
data class PostUpdateHookContext(
    val client: EntClient,
    val before: Post,
    val requestedPatch: PostUpdatePatch,
    val requestedEdges: PostPendingEdgeOps,
    val changes: PostUpdateMutationView,
) {
    @Deprecated("Use requestedPatch")
    val patch: PostUpdatePatch get() = requestedPatch

    @Deprecated("Use requestedEdges")
    val pendingEdges: PostPendingEdgeOps get() = requestedEdges

    @Deprecated("Use changes")
    val mutation: PostUpdateMutationView get() = changes
}
```

## Hook Context Shape

The hook context should focus on data available at hook time:

```kotlin
data class PostUpdateHookContext(
    val client: EntClient,
    val before: Post,
    val requestedPatch: PostUpdatePatch,
    val requestedEdges: PostPendingEdgeOps,
    val changes: PostUpdateMutationView,
)
```

`effectivePatch`, `candidate`, and computed `edgeChanges` are not available at
the first before hook today because update defaults and junction diffs happen
later. That ordering should remain explicit.

If hooks need to observe the final computed write, add a separate hook phase
rather than overloading `beforeUpdate`:

```kotlin
beforePersist { ctx ->
    ctx.before
    ctx.requestedPatch
    ctx.effectivePatch
    ctx.candidate
    ctx.edgeChanges
}
```

That phase is out of scope for this RFC, but the naming should leave room for
it.

## Public Update DSL

The public update DSL should not gain unset or edge-op mutation methods through
this cleanup. The current safety property remains:

- public update block can assign fields/FKs and use generated M2M helpers
- hook writable view can assign fields/FKs and call `unsetField()`
- hook writable view cannot call `save()`
- hook writable view cannot mutate the M2M op log

Renaming `mutation` to `changes` should not broaden the surface.

## Compatibility Plan

Because this project is greenfield, a breaking rename is acceptable. Still, a
short alias period may make generated API churn easier to review.

Option A: breaking rename.

- Generate only the new names.
- Update docs/tests/examples in one change.

Option B: alias period.

- Generate new names and deprecated old aliases.
- Update docs/examples to use new names.
- Remove old aliases later.

Prefer Option A if this lands before external users depend on the API.

## Documentation Plan

Update hook docs around one table:

```text
before: current database row
requestedPatch: scalar/FK intent accumulated so far
requestedEdges: edge intent captured before hooks
changes: restricted writable view for hook edits
```

Then show how privacy/validation receive later computed values:

```text
effectivePatch: requestedPatch plus update defaults
candidate: before folded with effectivePatch
edgeChanges: requestedEdges diffed against current junction rows
```

## Test Requirements

Before implementation, add tests for:

- generated hook context uses the chosen names
- old aliases are absent or deprecated, depending on migration option
- hook reads `requestedPatch` and writes through `changes`
- hook cannot call public save methods through `changes`
- hook cannot mutate requested M2M edge ops through `requestedEdges`
- privacy and validation contexts keep `requestedPatch`, `effectivePatch`,
  `candidate`, and `edgeChanges`
- docs snippets compile with the new names
