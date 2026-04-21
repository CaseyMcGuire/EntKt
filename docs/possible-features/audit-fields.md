# RFC: Audit Fields

## Status

Possible future feature. This is not implemented.

## Summary

Add generated support for common audit fields such as `created_at`,
`updated_at`, `created_by`, and `updated_by`.

## Motivation

Most applications need to know when a row was created, when it was last
updated, and which viewer performed those operations.

entkt already has privacy context, hooks, and generated write pipelines. Audit
fields can build on those mechanisms.

## Non-Goals

- Do not require privacy to use timestamp audit fields.
- Do not infer actor IDs from arbitrary application state.
- Do not support multi-actor audit trails in the first version.
- Do not replace a full event log.

## Proposed Schema API

Possible mixins:

```kotlin
override fun mixins() = listOf(
    timestamps(),
    actorStamps(),
)
```

Generated fields:

```kotlin
created_at
updated_at
created_by
updated_by
```

## Viewer Integration

For actor fields, generated code can read from `PrivacyContext.viewer`:

```kotlin
when (val viewer = privacy.viewer) {
    is Viewer.User -> viewer.id
    Viewer.System -> null
    Viewer.Anonymous -> null
}
```

The exact storage type needs schema configuration because `Viewer.User.id` is
currently `Any`.

## Test Requirements

Before implementation, add tests for:

- create sets `created_at` and `updated_at`
- update changes `updated_at`
- create sets `created_by` from viewer when configured
- update sets `updated_by` from viewer when configured
- `Viewer.System` behavior is documented and tested
- audit fields interact correctly with explicit user-provided values

