# RFC: Mutation Return LOAD Privacy

## Status

Possible future feature. This is not implemented.

## Summary

Decide whether mutation methods should enforce LOAD privacy on the entity they
return after a successful write.

Current write privacy answers whether the write may happen. Returned LOAD
privacy would additionally answer whether the caller may receive the persisted
entity in the mutation response.

## Motivation

Strict read semantics are easier to explain when every public entity return is
subject to LOAD privacy:

- `byId()` throws when the entity is unreadable
- `query.all()` throws when any matched entity is unreadable
- `firstOrNull()` throws when the fetched entity is unreadable
- write methods should not be able to return an unreadable row accidentally

Without returned LOAD checks, a caller may create or update a row and receive
fields that a normal read would reject.

## Non-Goals

- Do not roll back a successful write just because returned LOAD fails.
- Do not hide privacy denials as `null`.
- Do not run LOAD before after hooks.
- Do not change write privacy semantics.

## Proposed Behavior

For create:

```text
before hooks
field validation
create privacy
driver insert
hydrate entity
after create hooks
evaluate LOAD privacy on returned entity
return entity
```

For update:

```text
before hooks
field validation
update privacy
driver update
hydrate entity
after update hooks
evaluate LOAD privacy on returned entity
return entity
```

If returned LOAD privacy denies, `PrivacyDeniedException` is thrown and the
row remains persisted.

## API Shape

No new public API is required. The behavior changes existing methods:

```kotlin
client.posts.create { ... }.save()
client.posts.update(post) { ... }.save()
```

If callers need a write-only operation, that should be a separate future API,
not a silent bypass in the default entity-returning methods.

## Test Requirements

Before implementation, add tests for:

- create persists the row but throws if returned LOAD denies
- update persists the row but throws if returned LOAD denies
- after hooks run before returned LOAD privacy
- `Viewer.System` bypasses returned LOAD privacy

