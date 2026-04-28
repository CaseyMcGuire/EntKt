# RFC: Ephemeral Mutation Inputs

## Status

Possible future feature. This is not implemented.

## Summary

Add typed, non-persisted mutation inputs that travel with a generated write but
never become table columns.

These inputs exist only for the duration of a create, update, or delete
operation. They are available to validation, privacy-adjacent logic, derived
write behavior, and after-commit side effects.

## Motivation

Applications often need write-time inputs that are real operation parameters but
not real schema fields:

- `publishNow`
- `sendInviteEmail`
- `actorIp`
- `skipSearchIndexing`
- `invitedByUserId`
- `reason`

Today these values tend to get shoved into one of three bad places:

- temporary service-layer locals that hooks cannot see
- real database columns that should not exist
- loosely typed maps passed through application code

entkt needs a first-class place for these values in the write path.

## Non-Goals

- Do not turn ephemeral inputs into persisted fields.
- Do not expose ephemeral inputs on loaded entities.
- Do not make ephemeral inputs queryable or filterable.
- Do not create a process-global request bag.
- Do not require every mutation to declare ephemeral inputs.

## Proposed Semantics

Ephemeral inputs are:

- declared explicitly
- typed
- scoped to a mutation execution
- readable during normalization, validation, derivation, and after-commit work
- discarded after the mutation finishes

They are never part of:

- the generated entity model
- table DDL
- indexes
- foreign keys
- query APIs

## Example Use Cases

### Publish Now

```kotlin
client.posts.create {
    title = "Hello"
    body = "..."
    ephemeral {
        publishNow = true
    }
}.save()
```

The mutation pipeline can then:

- validate whether the current viewer may publish directly
- derive `status = PUBLISHED`
- set `published_at`
- enqueue search indexing after commit

without adding a `publish_now` column.

### Invite Email

```kotlin
client.memberships.create {
    userId = user.id
    teamId = team.id
    ephemeral {
        sendInviteEmail = true
        inviteMessage = "Welcome aboard"
    }
}.save()
```

After commit, the system can send an email only when requested.

## Possible API Shapes

### Builder-Attached

```kotlin
client.posts.create {
    title = "Hello"
    ephemeral {
        publishNow = true
        actorIp = "203.0.113.10"
    }
}.save()
```

### Action-Attached

```kotlin
CreatePostAction(client, viewer).save(
    CreatePostInput(
        title = "Hello",
        publishNow = true,
        actorIp = "203.0.113.10",
    )
)
```

### Declared Input Surface

Possible schema-adjacent declaration:

```kotlin
ephemeralInputs {
    bool("publishNow")
    string("actorIp").nullable()
}
```

This is the biggest open design choice:

- should ephemeral inputs be declared per entity
- per mutation kind
- or only at the action layer

The first version should prefer the smallest shape that still gives generated
pipelines and hooks typed access.

## Relationship To Structured Mutation Pipeline

This feature fits naturally with [Structured Mutation Pipeline](structured-mutation-pipeline.md):

- `normalize` can rewrite draft values based on ephemeral inputs
- `validate` can enforce rules like "only admins may set `publishNow = true`"
- `derive` can stamp final persisted fields
- `afterCommit` can use ephemeral flags to control side effects

Ephemeral inputs are much less compelling without a structured write lifecycle.

## Relationship To Privacy

Ephemeral inputs should not become a privacy bypass.

Acceptable uses:

- operation-level validation
- derived write behavior
- after-commit side effects

Question for implementation:

- should privacy rule contexts receive ephemeral inputs directly, or should the
  first version keep them out of privacy and reserve them for mutation
  validation/derivation only?

The conservative first version is to keep privacy rules unchanged unless there
is a concrete use case that cannot be modeled otherwise.

## Error Model

Ephemeral inputs should participate in normal validation errors:

- unknown input name -> clear schema/action configuration error
- wrong input type -> structured validation error
- invalid combination with persisted fields -> normal mutation validation error

This should not devolve into `Map<String, Any?>` casting failures at runtime.

## Test Requirements

Before implementation, add tests for:

- ephemeral inputs can be passed to a mutation without affecting table schema
- validation can read ephemeral inputs
- derive logic can use ephemeral inputs to change persisted columns
- after-commit logic can use ephemeral inputs
- loaded entities never expose ephemeral values
- ephemeral inputs do not appear in normalized schema metadata, SQL explain
  output, or migration snapshots
