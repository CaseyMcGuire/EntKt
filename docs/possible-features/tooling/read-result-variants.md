# RFC: Read Result Variants

## Status

Possible future feature. This is not implemented.

## Summary

Clarify generated read APIs by separating strict reads, nullable reads, and
structured read results.

## Motivation

Generated reads need to represent several different outcomes:

- row exists and is visible
- row does not exist
- row exists but LOAD privacy denies it
- storage fails

If every API returns either an entity or `null`, callers cannot distinguish
missing rows from denied rows. If every API throws, simple optional reads are
verbose.

## Non-Goals

- Do not weaken strict LOAD privacy.
- Do not change existing read behavior without a migration plan.
- Do not expose storage-driver internals in generated APIs.
- Do not make every caller handle a large sealed result type.

## Proposed API

Keep strict helpers for normal application reads:

```kotlin
client.users.byId(id)          // throws if missing or denied
client.users.first()           // throws if no visible row or denied row
```

Keep nullable helpers where absence is expected:

```kotlin
client.users.byIdOrNull(id)    // null if missing, throws if denied
client.users.firstOrNull()     // null if no row, throws if denied
```

Add structured result helpers for code that needs to distinguish every case:

```kotlin
when (val result = client.users.byIdResult(id)) {
    is ReadResult.Visible -> result.entity
    is ReadResult.Missing -> null
    is ReadResult.Denied -> audit(result.reason)
}
```

Possible result type:

```kotlin
sealed interface ReadResult<out T> {
    data class Visible<T>(val entity: T) : ReadResult<T>
    data object Missing : ReadResult<Nothing>
    data class Denied(val reason: PrivacyDeniedException) : ReadResult<Nothing>
}
```

## Naming Principles

Use names that reveal denial behavior:

- `byId` is strict
- `byIdOrNull` is nullable for missing rows, not privacy denial
- `byIdResult` exposes missing versus denied
- avoid names like `maybeById` that do not say what happens on denial

## Query APIs

For query builders:

```kotlin
query.first()
query.firstOrNull()
query.firstResult()
query.all()
```

`all()` should keep strict semantics: if a fetched row is denied, throw rather
than silently filtering, unless a future API explicitly opts into visible-only
filtering.

## Interaction With Loaders

Request-scoped loaders should reuse the same result model:

```kotlin
client.users.load(id)
client.users.loadOrNull(id)
client.users.loadResult(id)
```

This keeps batching and direct reads consistent.

## Test Requirements

Before implementation, add tests for:

- strict by-id throws for missing rows
- strict by-id throws for denied rows
- by-id nullable returns null for missing rows
- by-id nullable still throws for denied rows
- result reads distinguish missing and denied rows
- query first variants match by-id semantics
- loader variants match direct read semantics
