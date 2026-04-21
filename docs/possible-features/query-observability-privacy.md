# RFC: Query Observability Privacy

## Status

Possible future feature. This is not implemented.

## Summary

Add explicit query terminal methods for callers that need stricter control
over whether counts and existence checks can reveal unreadable rows.

## Motivation

`rawCount()` is intentionally fast and bypasses LOAD privacy. `visibleCount()`
materializes rows and filters denied ones. `exists()` currently checks one
materialized row.

Some applications may need stronger query-observability guarantees where a
caller cannot learn that unreadable rows exist.

## Non-Goals

- Do not remove `rawCount()`.
- Do not make every aggregate privacy-aware by default.
- Do not push arbitrary privacy rules into SQL in the first version.
- Do not hide the performance cost.

## Proposed API

Add strict terminal operations:

```kotlin
query.checkedCount()
query.checkedExists()
```

Semantics:

- materialize matching rows
- evaluate LOAD privacy on each row considered
- throw `PrivacyDeniedException` on the first denied entity
- return only if every relevant row is allowed

Keep existing explicit methods:

```kotlin
query.rawCount()      // fast, no LOAD privacy
query.visibleCount()  // filters denied rows
```

## Exists Semantics

`checkedExists()` should not stop at the first driver row if that row is
denied and later rows might be visible. Strict semantics are about the query
set, not only the first row.

One possible contract:

- fetch all matching rows
- throw if any row is denied
- return true if at least one row exists
- return false if no row exists

This is slower but easier to explain.

## Test Requirements

Before implementation, add tests for:

- `checkedCount()` throws if any matched row is denied
- `checkedCount()` returns count when all matched rows are allowed
- `checkedExists()` throws if any matched row is denied
- `checkedExists()` returns false when no row matches
- docs clearly compare raw, visible, and checked variants

