# RFC: Structured Mutation Pipeline

## Status

Possible future feature. This is not implemented.

## Summary

Make generated writes run through a first-class mutation pipeline with
well-defined phases:

```text
normalize -> validateInput -> derive -> authorize -> validateInvariant
          -> persist -> afterPersist -> afterCommit
```

The goal is not to add "more hooks". The goal is to give entkt one
predictable mutation contract so privacy, validation, derived fields,
application callbacks, and side effects all happen in a defensible order.

## Motivation

entkt already has:

- generated create, update, and delete builders
- field and entity validation
- privacy checks
- lifecycle hooks such as `beforeSave`, `beforeCreate`, `afterCreate`

That is enough for simple cases, but it leaves some important questions loose:

- Which callbacks are allowed to mutate the pending write?
- Which checks are request/input validation vs entity validation?
- Where do derived fields like timestamps or slugs belong?
- Which code is allowed to run side effects before commit?
- How should action-style application operations compose with generated writes?

This feature gives the write path a more explicit structure without replacing
generated builders.

## Non-Goals

- Do not replace generated create, update, or delete builders.
- Do not require every application mutation to use a separate action class.
- Do not add nested graph persistence in the first version.
- Do not weaken privacy or validation guarantees.
- Do not turn entity mutation into an arbitrary middleware chain.

## Proposed Semantics

Every generated write runs through the same conceptual phases:

1. `normalize`
2. `validateInput`
3. `derive`
4. `authorize`
5. `validateInvariant`
6. `persist`
7. `afterPersist`
8. `afterCommit`

### Normalize

Purpose:

- rewrite raw input into a cleaner draft
- fill request-level defaults
- trim strings
- canonicalize case or whitespace

Allowed:

- mutate the pending draft

Not allowed:

- external side effects
- database writes outside the pending entity write

### Validate Input

Purpose:

- field validation
- required-field checks
- reject malformed local input before policy or database reads

Allowed:

- read the normalized input draft
- reject the mutation with one or more errors

Not allowed:

- external side effects
- cross-entity reads
- authorization decisions

### Derive

Purpose:

- compute final persisted values that depend on input-valid values or current state
- set timestamps
- compute slugs
- stamp actor IDs
- convert approved state transitions into persisted columns

Allowed:

- mutate the pending draft

Not allowed:

- external side effects

Derivation completes before authorization and invariant validation so both
phases inspect the same final candidate that persistence will receive. If one
derived value can itself violate a field constraint, the framework runs the
relevant constraint against the final candidate before persistence.

### Authorize

Purpose:

- evaluate CREATE, UPDATE, or DELETE privacy
- decide whether the viewer may perform this exact final write

Allowed:

- read the current row, requested changes, final candidate, edge changes,
  privacy context, and policy read client
- reject with the existing structured privacy failure

Not allowed:

- mutate the final candidate
- external side effects

Authorization precedes invariant validation so an unauthorized caller cannot
use cross-entity validation failures as a data oracle. Local input validation
already ran because malformed request shape does not inspect protected stored
data.

### Validate Invariant

Purpose:

- entity and cross-field validation
- cross-entity invariants
- state-transition checks
- final field checks after derivation

Allowed:

- read the authorized final candidate, current state, edge changes, ephemeral
  inputs, and validation read client
- return one or more structured violations

Not allowed:

- mutate the final candidate
- external side effects

### Persist

Purpose:

- perform the actual insert, update, or delete
- enforce database constraints
- return the persisted entity

This remains framework-owned.

### After Persist

Purpose:

- observe the row written by the database
- perform additional work that intentionally belongs to the same transaction
- register post-commit effects

`afterPersist` replaces the ambiguous interpretation of today's
`afterCreate`, `afterUpdate`, and `afterDelete`: the write has happened, but an
enclosing transaction may still roll back.

Allowed:

- transaction-bound database work through the current scoped client
- registering `afterCommit` callbacks or outbox records

Not allowed:

- claiming that the transaction committed
- assuming external messages or network calls can be rolled back

### After Commit

Purpose:

- enqueue jobs
- emit events
- send notifications
- update external systems

Allowed:

- side effects that should happen only if the transaction commits

Not allowed:

- mutating the already-persisted row

An `afterCommit` callback runs only after EntKt confirms the owning transaction
committed. For a caller-owned transaction, callbacks registered by individual
saves are deferred to the outermost transaction boundary and run in
registration order.

If an `afterCommit` callback fails, the database commit remains successful. The
failure must therefore carry committed write state and must never be reported
as though retrying the mutation were safe. Applications requiring reliable
delivery should write an outbox row in `afterPersist` and deliver it separately.

## Relationship To Existing Hooks

This feature should tighten the write lifecycle, not throw away the existing
hook model on day one.

Plausible mapping:

- `beforeSave` and operation-specific before hooks become compatibility adapters
  over documented mutable pre-persist phases
- today's `afterCreate`, `afterUpdate`, and `afterDelete` semantics are named
  `afterPersist`
- a new `afterCommit` surface owns effects that must not run on rollback

The important design point is that hooks should stop being "arbitrary callbacks
somewhere around save" and instead attach to named pipeline phases with clear
rules.

## Example Shape

Possible client configuration:

```kotlin
val client = EntClient(driver) {
    mutations {
        posts {
            normalize { draft ->
                draft.title = draft.title?.trim()
            }

            validateInput { ctx ->
                if (ctx.draft.title.isNullOrBlank()) {
                    error("title is required")
                }
            }

            derive { ctx ->
                if (ctx.op == MutationOp.CREATE) {
                    ctx.draft.createdAt = Instant.now()
                }
                ctx.draft.updatedAt = Instant.now()
            }

            authorize { ctx ->
                if (ctx.privacy.viewer is Viewer.Anonymous) {
                    PrivacyDecision.Deny("authentication required")
                } else {
                    PrivacyDecision.Allow
                }
            }

            validateInvariant { ctx ->
                if (ctx.candidate.published && ctx.candidate.body.isBlank()) {
                    invalid("published posts require a body", field = "body")
                }
            }

            afterPersist { ctx ->
                ctx.client.outbox.create {
                    topic = "post.changed"
                    entityId = ctx.entity.id
                }.save().orRollback()
            }

            afterCommit { post ->
                jobs.enqueue(IndexPost(post.id))
            }
        }
    }
}
```

The exact API surface is open. The important part is the lifecycle contract.

## Relationship To Mutation Actions

This proposal overlaps with [Mutation Actions](mutation-actions.md), but at a
different layer:

- **Structured Mutation Pipeline** defines the inner write lifecycle used by
  generated entity mutations.
- **Mutation Actions** define a larger application operation boundary around one
  or more generated mutations.

Actions should be able to rely on the pipeline rather than re-implementing the
write contract themselves.

## Relationship To Audit Fields

[Audit Fields](../schema/audit-fields.md) becomes cleaner if the framework has a dedicated
`derive` phase. Timestamp and actor stamping are a much better fit there than
in ad hoc `beforeSave` hooks.

## Error Model

Failures should stay structured:

- normalization failures should identify the field or input source
- input and invariant validation failures should preserve entity and field context
- privacy denial should preserve the current privacy error shape
- database constraint failures should keep current behavior
- after-commit failure should state that persistence is already committed

This feature should not force applications back into raw exception parsing.

## Test Requirements

Before implementation, add tests for:

- normalization runs before input validation
- input validation performs no policy or cross-entity reads
- derivation produces the candidate seen by authorization and invariant validation
- authorization denial prevents invariant validation and persistence
- invariant validation stops the write before any database mutation
- after-persist callbacks run inside and can roll back with the transaction
- after-commit callbacks do not run when the transaction rolls back
- caller-owned transactions defer callbacks to the outermost confirmed commit
- after-commit failure reports committed state and never implies safe mutation retry
- privacy and validation still use the same viewer and candidate values the
  rest of entkt already enforces
- generated hooks and pipeline phases run in documented order
- transactional clients preserve the same pipeline semantics
