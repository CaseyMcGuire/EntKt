# RFC: Structured Mutation Pipeline

## Status

Possible future feature. This is not implemented.

## Summary

Make generated writes run through a first-class mutation pipeline with
well-defined phases:

```text
normalize -> validate -> derive -> persist -> afterCommit
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
2. `validate`
3. `derive`
4. `persist`
5. `afterCommit`

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

### Validate

Purpose:

- field validation
- entity validation
- privacy checks
- cross-field checks
- state transition checks

Allowed:

- read current entity state, draft values, privacy context, and ephemeral inputs
- reject the mutation with one or more errors

Not allowed:

- external side effects

Open question:

- Should framework-owned derived fields that are required at persistence time be
  applied before validation, or should the framework run a final invariant check
  after `derive`? The first version should pick one rule and document it
  sharply.

### Derive

Purpose:

- compute final persisted values that depend on validated inputs or current state
- set timestamps
- compute slugs
- stamp actor IDs
- convert approved state transitions into persisted columns

Allowed:

- mutate the pending draft

Not allowed:

- external side effects

### Persist

Purpose:

- perform the actual insert, update, or delete
- enforce database constraints
- return the persisted entity

This remains framework-owned.

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

## Relationship To Existing Hooks

This feature should tighten the write lifecycle, not throw away the existing
hook model on day one.

Plausible mapping:

- `beforeSave` and `beforeCreate` become structured pre-persist stages
- `afterCreate`, `afterUpdate`, and `afterDelete` remain post-persist callbacks
- a new `afterCommit` surface may be needed for side effects that must not run
  on rollback

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

            validate { ctx ->
                if (ctx.draft.title.isNullOrBlank()) {
                    error("title is required")
                }
                if (ctx.privacy.viewer is Viewer.Anonymous) {
                    error("authentication required")
                }
            }

            derive { ctx ->
                if (ctx.op == MutationOp.CREATE) {
                    ctx.draft.createdAt = Instant.now()
                }
                ctx.draft.updatedAt = Instant.now()
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

[Audit Fields](audit-fields.md) becomes cleaner if the framework has a dedicated
`derive` phase. Timestamp and actor stamping are a much better fit there than
in ad hoc `beforeSave` hooks.

## Error Model

Failures should stay structured:

- normalization failures should identify the field or input source
- validation failures should preserve entity and field context
- privacy denial should preserve the current privacy error shape
- database constraint failures should keep current behavior

This feature should not force applications back into raw exception parsing.

## Test Requirements

Before implementation, add tests for:

- normalization runs before validation
- validation stops the write before any database mutation
- derive can rewrite final persisted values
- after-commit callbacks do not run when the transaction rolls back
- privacy and validation still use the same viewer and candidate values the
  rest of entkt already enforces
- generated hooks and pipeline phases run in documented order
- transactional clients preserve the same pipeline semantics
