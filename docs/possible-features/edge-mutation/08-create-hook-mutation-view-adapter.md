# RFC: Create Hook Mutation View Adapter

## Status

Possible future feature. This is not implemented.

Extracted from the implemented
[To-One FK Mutation And Nullability](../../implemented-features/edge-mutation/02-to-one-assignment-nullability.md)
RFC.

## Summary

Create hooks should receive dedicated hook-facing mutation view adapters instead
of the concrete create builder. The current generated API gives hooks a
restricted static type, but the runtime object is still the concrete
`${Entity}Create` builder.

## Motivation

Update hooks already use private adapter objects so hook-facing APIs are not
just static type restrictions. Create hooks should follow the same model:

- `beforeSave` on create should see only `${Entity}Mutation`
- `beforeCreate` should see only `${Entity}CreateMutationView`
- neither hook should receive an object that can be cast back to
  `${Entity}Create` to reach `save()`, `driver`, hook lists, or private staging
  fields

This is API-surface hardening, not a security boundary. It keeps generated hook
semantics consistent and makes tests match the documented contract.

## Design

Generate two private adapters inside `${Entity}Create`:

- `_beforeSaveView: ${Entity}Mutation`
- `_createMutationView: ${Entity}CreateMutationView`

Both adapters forward allowed property reads and writes to the outer create
builder. They do not implement the concrete create builder type.

The save path should run:

```kotlin
for (hook in beforeSaveHooks) hook(_beforeSaveView)
val createCtx = EntityCreateHookContext(client, _createMutationView)
for (hook in beforeCreateHooks) hook(createCtx)
```

## Read And Write Behavior

The adapters should preserve existing create builder property semantics:

- reading an unset required scalar or FK still throws the generated usage error
- reading an untouched nullable scalar or FK returns `null`
- writing a required FK still rejects Java/platform `null`
- nullable field-backed FK assignment still preserves explicit-null-wins
  default behavior

## Non-Goals

- Do not change public create builder methods.
- Do not add `unset{Property}()` to create mutation views.
- Do not change update hook adapters.

## Acceptance Criteria

- `beforeSave` create hooks receive an object implementing only
  `${Entity}Mutation`.
- `beforeCreate` hooks receive an object implementing only
  `${Entity}CreateMutationView`.
- A hook cannot cast `ctx.mutation` back to `${Entity}Create`.
- All existing create hook read/write semantics remain unchanged.
- Tests cover required FK reads, nullable FK reads, writes through the adapter,
  and failed casts to the concrete create builder.
