# RFC: Create Hook Mutation View Adapter

## Status

**V1 implemented.** Adapter generation ships in
`codegen/src/main/kotlin/entkt/codegen/CreateGenerator.kt`:
private `_beforeSaveView` / `_createMutationView` properties on
every generated `${Entity}Create`, with the save body now passing
them to `beforeSave` / `beforeCreate` hooks (replacing the V0
`hook(this)` / `CreateHookContext(client, this)` shape). Updated
`MutationGenerator.kt` doc-comment + the §"Hook-Facing API Shape"
section of RFC 02 reflect the post-RFC-08 contract. Test coverage
lives in `codegen/.../CreateGeneratorTest.kt` (codegen-string
checks for the adapter properties, hook callsite, and immutable-FK
filter regression) and `integration-tests/.../CreateHookAdapterIntegrationTest.kt`
(end-to-end Postgres-backed: 4 disallowed casts + 1 allowed
widening + read/write behavior through both adapters).

Extracted from the implemented
[To-One FK Mutation And Nullability](02-to-one-assignment-nullability.md)
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

### Relationship between `${Entity}Create` and `${Entity}CreateMutationView`

The concrete `${Entity}Create` builder **continues to implement
`${Entity}CreateMutationView`** (and through it, `${Entity}Mutation`).
That class-hierarchy relationship is unchanged by this RFC — only the
runtime object handed to each hook changes.

This is the smallest-possible change: the existing interface
implementation chain stays in place so non-hook code that interacts
with the builder via the view types (e.g. utility extension
functions, tests that hand a builder into a view-typed parameter)
keeps working. The hardening that this RFC adds applies only at
the hook-invocation boundary, where the framework substitutes the
private adapters for the concrete builder.

Concretely:

| Surface | Runtime type a hook receives |
|---|---|
| `beforeSave` (create path) | `_beforeSaveView` — implements `${Entity}Mutation` only |
| `beforeCreate` (`ctx.mutation`) | `_createMutationView` — implements `${Entity}CreateMutationView` only |
| Direct code that constructs / interacts with `${Entity}Create` | concrete `${Entity}Create`, still implements `${Entity}CreateMutationView` and `${Entity}Mutation` |

A future RFC could remove the `${Entity}Create : ${Entity}CreateMutationView`
implementation if there is a use case, but doing so requires churn
across any code that relies on the implicit upcast and is explicitly
out of scope here.

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

## Documentation Cleanup Done On Implementation

The V0 docs described create hooks as receiving the concrete
builder typed as the view ("static API restriction only").
Implementation landed alongside the following doc updates so
future readers don't get the wrong mental model:

- **`codegen/src/main/kotlin/entkt/codegen/MutationGenerator.kt`**
  — class doc-comment rewritten: both create and update views
  are described as runtime-enforced via private anonymous
  adapter properties, with `_createMutationView` named
  explicitly and the `${Entity}Create : ${Entity}CreateMutationView`
  class-hierarchy relationship called out as preserved for
  non-hook callers.
- **`docs/implemented-features/edge-mutation/02-to-one-assignment-nullability.md`**
  §"Hook-Facing API Shape" — the V0 asymmetry ("create as
  static-only, update as runtime-enforced") flipped to
  "both runtime-enforced after RFC 08." The test-requirements
  summary at the bottom updated to match.
- **`codegen/src/main/kotlin/entkt/codegen/CreateGenerator.kt`**
  inline comment in `emitCreateBody` — the V0 "static API
  restriction" note replaced with a pointer at RFC 08 explaining
  the new runtime-enforced contract.

No remaining static-only narrowing language in the codebase or
docs as of this RFC's implementation. The cast-failure test
suite in `CreateHookAdapterIntegrationTest` mechanically guards
against regressing back to the static-only shape.

## Acceptance Criteria

### Runtime types handed to hooks

- `beforeSave` create hooks receive an object implementing only
  `${Entity}Mutation`.
- `beforeCreate` hooks receive an object whose `ctx.mutation`
  implements only `${Entity}CreateMutationView`.

### Cast-failure requirements

Every hook-facing path must reject casts back to the concrete
builder AND to sibling view / builder types that the adapter
intentionally doesn't implement. Specifically, all of the
following must throw `ClassCastException` (or fail to compile if
statically typed):

- `beforeSave` hook arg cannot be cast to `${Entity}Create`.
- `beforeSave` hook arg cannot be cast to `${Entity}CreateMutationView`
  — the create-only `${Entity}Mutation` view deliberately
  excludes immutable-field setters, so widening up to
  `${Entity}CreateMutationView` from a `beforeSave` adapter
  would let a `beforeSave` hook reach members that are
  create-phase-specific.
- `beforeCreate` `ctx.mutation` cannot be cast to `${Entity}Create`.
- `beforeCreate` `ctx.mutation` cannot be cast to
  `${Entity}UpdateMutationView` — the create-view adapter must
  not accidentally satisfy the update-side view interface; a
  hook that managed this cast would reach `unset{X}()` methods
  that don't apply on the create path.

The one allowed widening:

- `beforeCreate` `ctx.mutation` cast to `${Entity}Mutation`
  succeeds (the create view extends `${Entity}Mutation` per
  the MutationGenerator design) — this is the parent interface
  the create view declares as its supertype.

### Behavioral compatibility

- All existing create hook read/write semantics remain
  unchanged (see §"Read And Write Behavior").
- The concrete `${Entity}Create` continues to implement
  `${Entity}CreateMutationView` and `${Entity}Mutation` (see
  §"Relationship between …" in §"Design"); non-hook code that
  upcasts the builder to either view type keeps working.

### Test coverage

- Required FK reads, nullable FK reads, writes through each
  adapter.
- All four failed-cast cases listed above (two `beforeSave`,
  two `beforeCreate`).
- The one permitted widening (`ctx.mutation as ${Entity}Mutation`
  in a `beforeCreate` hook) is exercised as a positive test.
