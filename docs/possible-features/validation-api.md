# RFC: Validation API

## Status

**Superseded by [docs/validation.md](../validation.md).** The final
design uses privacy-before-validation ordering (reversed from this
RFC) and `ValidationDecision.Valid`/`Invalid` instead of
`Continue`/`Reject`. See the spec for the accepted design.

## Summary

Add entity-scoped validation rules that can reject writes which would leave
the data model in an invalid state.

Validation should be separate from privacy. Privacy answers whether the viewer
may perform an operation. Validation answers whether the candidate data is
valid regardless of who is performing the operation.

## Motivation

Applications need invariants that are too domain-specific for field validators
and too data-focused for privacy rules:

- post authors cannot change after creation
- friendships cannot point from a user to themselves
- duplicate friendship requests are rejected
- posts with comments cannot be deleted
- status transitions must follow an allowed state machine

Today these checks tend to live in hooks or application service code. A
validation API would make them explicit, generated, and consistently enforced.

## Non-Goals

- Do not merge validation and privacy into one rule list.
- Do not replace schema field validators.
- Do not replace database constraints.
- Do not generate SQL predicates from validation rules in the first version.
- Do not add asynchronous validation in the first version.

## Proposed API

Policies could configure validation next to privacy:

```kotlin
object PostPolicy : EntityPolicy<Post, PostPolicyScope> {
    override fun configure(scope: PostPolicyScope) = scope.run {
        validation {
            create(PostValidation.authorExists)
            update(PostValidation.authorCannotChange)
            delete(PostValidation.cannotDeleteWithComments)
        }
    }
}
```

Rules are checked in order:

```kotlin
fun interface ValidationRule<in C> {
    fun run(ctx: C): ValidationDecision
}

sealed interface ValidationDecision {
    data object Continue : ValidationDecision
    data class Reject(val reason: String) : ValidationDecision
}
```

Unlike privacy, there is no `Allow`. Validation rules are requirements and
guards; a rule either rejects or continues.

## Operation Contexts

Validation can reuse the write candidate machinery generated for privacy:

```kotlin
data class PostCreateValidationContext(
    val client: EntClient,
    val candidate: PostWriteCandidate,
)

data class PostUpdateValidationContext(
    val client: EntClient,
    val before: Post,
    val candidate: PostWriteCandidate,
)

data class PostDeleteValidationContext(
    val client: EntClient,
    val entity: Post,
)
```

Validation should not require `PrivacyContext`, because validation is not
viewer-specific. If a rule needs viewer-aware behavior, it is probably a
privacy rule.

## Enforcement Order

Recommended write order:

1. run before hooks
2. resolve defaults and candidates
3. run field validators
4. run validation rules
5. run privacy rules
6. execute the database write
7. run after hooks
8. enforce returned LOAD privacy if that contract is adopted

Validation before privacy gives callers deterministic data errors when input
is invalid. Privacy before validation can avoid existence leaks, but it makes
data failures harder to reason about. This order should be revisited if
query-observability privacy becomes a goal.

## Test Requirements

Before implementation, add tests for:

- create validation rejects before insert
- update validation sees `before` and `candidate`
- delete validation can query related entities
- validation rejection leaves the database unchanged
- validation and privacy both run in the documented order
- validation rules run for bulk convenience methods per item

