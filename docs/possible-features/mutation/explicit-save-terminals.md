# RFC: Explicit Save Terminals

## Status

Possible future feature. This is not implemented.

## Summary

Remove or de-emphasize the plain generated `save()` methods in favor of
outcome-explicit terminals:

```kotlin
create { ... }.saveOrThrow()
create { ... }.saveOrError()

update(id) { ... }.saveOrNull()
update(id) { ... }.saveOrThrow()
update(id) { ... }.saveOrError()
```

The goal is to make the operation result shape obvious at the call site.

## Motivation

The plain `save()` name hides different contracts:

- create `save()` returns the created entity or throws
- update `save()` returns the updated entity or `null` for a missing owner row
- both can throw for privacy, validation, constraint, transaction, or driver
  failures

That is compact, but it is not the least-surprise API. A caller reading:

```kotlin
val user = client.users.update(id) { name = "Alice" }.save()
```

must already know that `user` is nullable and that `null` means only "owner row
not found", not privacy denial or validation failure. The existing explicit
terminals communicate that directly:

```kotlin
val user = client.users.update(id) { name = "Alice" }.saveOrNull()
val user = client.users.update(id) { name = "Alice" }.saveOrThrow()
val result = client.users.update(id) { name = "Alice" }.saveOrError()
```

## Goals

- Make write result contracts visible in method names.
- Keep throwing and structured-result paths first-class.
- Preserve the narrow meaning of `null`: expected absence only.
- Avoid teaching users a special case where create and update `save()` have
  different nullability.

## Non-Goals

- Do not remove `saveOrThrow()`, `saveOrError()`, or update `saveOrNull()`.
- Do not change privacy, validation, hook, transaction, or driver semantics.
- Do not collapse privacy, validation, or driver failures into `null`.
- Do not introduce a new result type beyond `EntResult`.

## Proposed API

### Preferred Terminals

Generated create builders expose:

```kotlin
fun saveOrThrow(): Entity
fun saveOrError(): EntResult<Entity>
```

Generated update builders expose:

```kotlin
fun saveOrNull(): Entity?
fun saveOrThrow(): Entity
fun saveOrError(): EntResult<Entity>
```

The docs and examples should use these explicit terminals by default.

### Plain `save()`

There are three viable options.

Option A: remove public `save()`.

Generated code may keep a private/internal implementation helper, but
application code cannot call plain `save()`. This is the cleanest final API.

Option B: keep `save()` temporarily as deprecated.

```kotlin
@Deprecated(
    message = "Use saveOrThrow(), saveOrError(), or saveOrNull() so the result contract is explicit.",
    replaceWith = ReplaceWith("saveOrThrow()"),
)
fun save(): Entity
```

For update, the replacement should be `saveOrNull()` or `saveOrThrow()`
depending on caller intent, so the generated deprecation message may need to
name both options rather than provide a single `ReplaceWith`.

Option C: keep `save()` as an internal generated helper only.

`saveOrThrow()` and `saveOrError()` delegate to an internal `saveInternal()`
rather than exposing plain `save()` as public API.

## Recommendation

Because this project is greenfield and breaking changes are acceptable, prefer
Option A or C:

- public API exposes only explicit terminals
- generated internals may share implementation through a non-public helper
- docs never show plain `save()`

If downstream example churn is too large for one change, use Option B as a
short transitional step.

## Error Semantics

The explicit terminals keep the existing result-variants contract:

- `saveOrThrow()` returns the entity or throws a structured `EntException`
- `saveOrError()` returns `EntResult.Ok(entity)` or `EntResult.Err(error)`
- update `saveOrNull()` returns `null` only for missing owner rows
- `TransactionRequiredException` and `UnsupportedDriverCapabilityException`
  remain deterministic configuration/programming errors and are not collapsed
  into `EntResult`

Create does not need `saveOrNull()` because create has no expected absence case.

## Migration Plan

1. Update docs and examples to use `saveOrThrow()`, `saveOrError()`, or
   update `saveOrNull()`.
2. Change generated create/update builders to stop exposing public `save()`,
   or mark it deprecated for one release cycle.
3. Keep one generated implementation path so `saveOrThrow()` and
   `saveOrError()` cannot diverge.
4. Update tests that assert generated members and example snippets.

## Test Requirements

Before implementation, add or update tests for:

- create builders expose `saveOrThrow()` and `saveOrError()`
- update builders expose `saveOrNull()`, `saveOrThrow()`, and `saveOrError()`
- public `save()` is absent or deprecated, depending on the chosen option
- generated examples compile without plain `save()`
- `saveOrThrow()` and `saveOrError()` preserve existing privacy, validation,
  constraint, and driver failure mappings
- update `saveOrNull()` returns `null` only for missing owner rows
