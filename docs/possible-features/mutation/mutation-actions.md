# RFC: Mutation Actions

## Status

Possible future feature. This is not implemented.

## Summary

Add an optional action layer above generated create, update, delete, and edge
mutation builders.

Actions would package input parsing, validation, privacy checks, hooks, and
persistence into named application operations.

## Motivation

Generated builders are a good low-level mutation surface:

```kotlin
client.posts.create {
    title = input.title
    author.connect(author)
}.save()
```

Applications often need a more explicit operation boundary:

```kotlin
CreatePostAction(client, viewer).save(
    CreatePostInput(
        title = "Hello",
        authorId = author.id,
    )
)
```

That boundary is useful when mutations need:

- request DTO validation
- multi-entity checks
- structured domain errors
- transaction wrapping
- after-commit side effects
- explicit test fixtures for business operations

## Non-Goals

- Do not replace generated builders.
- Do not require every mutation to be an action.
- Do not add nested graph persistence in the first version.
- Do not let actions bypass privacy, validation, or hooks.
- Do not generate HTTP or GraphQL endpoints from actions in this feature.

## Proposed Shape

Actions can be handwritten classes that compose generated APIs:

```kotlin
class CreatePostAction(
    private val client: EntClient,
    private val viewer: Viewer,
) {
    suspend fun save(input: CreatePostInput): Post {
        validateInput(input)

        return client.withPrivacyContext(viewer) {
            withTransaction { tx ->
                val author = tx.users.byId(input.authorId)

                tx.posts.create {
                    title = input.title
                    this.author.connect(author)
                }.save()
            }
        }
    }
}
```

Generated helpers could make this pattern less repetitive without forcing a
large framework:

```kotlin
abstract class EntAction<Input, Output> {
    open suspend fun validate(input: Input) {}
    abstract suspend fun run(input: Input): Output
}
```

## Lifecycle

The action lifecycle should be explicit:

1. parse or receive typed input
2. validate request-level input
3. enter privacy context
4. optionally open transaction
5. run generated entity mutations
6. run after-commit side effects if the transaction succeeds
7. return the generated entity or domain result

Entity-level privacy, validation, and hooks still run inside the generated
mutation pipeline. Action validation is an outer application layer, not a
replacement.

## Transaction Semantics

Actions should make transactions easy but not implicit for every operation.

Possible helper:

```kotlin
suspend fun <T> EntClient.actionTransaction(
    block: suspend EntClient.() -> T,
): T
```

Open question:

- Should actions default to a transaction, or require each action to opt in?

The safer default is explicit opt-in. Some actions are read-only or perform a
single generated mutation where the extra transaction wrapper is not needed.

## Side Effects

Actions are a better home than entity hooks for application side effects:

- sending email
- enqueueing jobs
- writing audit events outside the entity table
- calling external services

The first version should only run side effects after the database transaction
commits. Running side effects before commit makes rollback behavior hard to
reason about.

## Test Requirements

Before implementation, add tests for:

- action validation runs before generated mutation writes
- generated privacy and validation still run inside actions
- transaction-wrapped actions roll back all generated writes on failure
- after-commit side effects do not run when the transaction fails
- action helpers preserve structured generated errors
