# Breaking Changes

entkt is pre-1.0 and not yet used in production, so breaking changes are
expected and intentional (see the [project principles](../../AGENTS.MD)).
This file is the single running log of every breaking change to the public
surface — the schema DSL, the generated API (entities, repos, query
builders, `indexes` helpers), the runtime, the drivers, and the Gradle
plugin. **Newest first.**

This is the source of truth for "what do I have to change when I bump
entkt." The numbered user guides describe the *current* API; this log
describes how it *changed*.

## Adding an entry

Add a bullet under `## Unreleased` (create that section if it's missing),
newest at the top, using this shape:

```text
- **<short imperative summary>** (`affected-module`)
  <what changed and why, in a sentence or two>
  _Migration:_ <the concrete change a caller must make>
```

Keep it caller-focused: what breaks and what to do about it, not the
internal rationale (link an [implemented-features](../implemented-features/index.md)
note for the full design). When cutting a release, rename `## Unreleased`
to the version (e.g. `## 0.2.0`) and start a fresh empty `## Unreleased`
above it.

## Unreleased

- **`Field.jsonClass` constructor parameter replaced by `Field.jsonType: KType`** (`schema`)
  JSON fields now capture the full Kotlin type (with type arguments) so
  `json<List<Rect>>("rects")` generates a `List<Rect>` property and registers
  an element-typed serializer — a `KClass` cannot carry type arguments, which
  is why generic JSON fields previously emitted a raw `List`. `Field.jsonClass`
  remains as a derived read-only property (the raw classifier), so reads keep
  compiling; only constructing/copying `Field` with a named `jsonClass`
  argument breaks. `json(name, klass)` now rejects classes with type
  parameters (use the reified overload). `FieldBuilder.setJsonClass` is now
  `setJsonType(KType)`.
  _Migration:_ pass `jsonType = typeOf<X>()` instead of `jsonClass = X::class`
  when constructing `Field` directly; schema DSL callers (`json(...)`) need no
  change.

- **`entkt.runtime` split into concern-based subpackages** (`runtime`)
  Runtime types moved from the flat `entkt.runtime` package into
  `entkt.runtime.{driver,privacy,validation,query,mutation,result}`.
  _Migration:_ update imports — e.g. `entkt.runtime.Viewer` →
  `entkt.runtime.privacy.Viewer`, `entkt.runtime.Driver` →
  `entkt.runtime.driver.Driver`, `entkt.runtime.EntResult` →
  `entkt.runtime.result.EntResult`. Generated code already targets the new
  packages; only hand-written imports need updating. See the mapping in
  [runtime/README.md](../../runtime/README.md#package-layout).
