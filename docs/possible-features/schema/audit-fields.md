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

Possible `EntMixin` bundles, using the existing host-bound `include` API
(the audit bundles themselves are proposed):

```kotlin
val timestamps = include(::Timestamps)
val actors = include(::ActorStamps)
```

Generated fields:

```kotlin
created_at
updated_at
created_by
updated_by
```

## Viewer Integration

The viewer is a type the application defines, so the runtime cannot read an
actor ID from it. The application supplies an accessor over its viewer type,
and generated code branches on the two context cases:

```kotlin
when (viewerContext) {
    is ViewerContext.AsViewer -> actorId(viewerContext.viewer) // application-supplied accessor
    is ViewerContext.PrivacyBypass -> null
}
```

The accessor's return type can be checked against the column type of
`created_by` and `updated_by`, so no extra schema configuration is needed to
choose a storage type. An accessor that returns `null`, for an unauthenticated
viewer, leaves the actor `null`.

## Test Requirements

Before implementation, add tests for:

- create sets `created_at` and `updated_at`
- update changes `updated_at`
- create sets `created_by` from viewer when configured
- update sets `updated_by` from viewer when configured
- `ViewerContext.PrivacyBypass` behavior is documented and tested
- audit fields interact correctly with explicit user-provided values

