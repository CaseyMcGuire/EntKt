# RFC: Privacy / Validation Explain Mode

## Status

Possible future feature. This is not implemented.

## Summary

Add a debugging API that reports how privacy or validation rules evaluated for
an operation.

This is intended for tests, development, and observability. It should not
change enforcement semantics.

## Motivation

Rule lists become hard to debug as applications grow:

- a LOAD rule may continue unexpectedly
- a later rule may deny after an earlier rule looked permissive
- derived write privacy can be hard to trace
- validation failures may depend on data loaded through `ctx.client`

An explain API would make rule execution inspectable without adding logging to
every rule.

## Non-Goals

- Do not expose explain output as a security boundary.
- Do not run explain automatically for every request.
- Do not require rule authors to implement a new interface.
- Do not leak explain details to end users by default.

## Proposed API

Example:

```kotlin
val report = client.posts.explainPrivacy {
    load(post)
}
```

Possible report model:

```kotlin
data class RuleTrace(
    val ruleName: String,
    val decision: String,
    val reason: String?,
    val elapsedNanos: Long,
)

data class PolicyTrace(
    val entity: String,
    val operation: PrivacyOperation,
    val finalDecision: String,
    val rules: List<RuleTrace>,
)
```

Validation could use a parallel shape:

```kotlin
client.posts.explainValidation {
    update(post) { title = "" }
}
```

## Rule Names

The first version can use `rule::class.qualifiedName ?: rule.toString()`.
Later versions could add an optional naming interface:

```kotlin
interface NamedRule {
    val ruleName: String
}
```

## Test Requirements

Before implementation, add tests for:

- allow, continue, and deny decisions are recorded
- final decision matches actual enforcement
- derived privacy rules appear in the trace
- explain mode does not mutate data
- explain output redacts sensitive values by default

