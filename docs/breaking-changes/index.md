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

- **Eager-load interceptors fire on empty results** (`codegen`)
  The EAGER_LOAD interceptor pass now runs on every configured
  `with{Edge}()` subquery even when the root query matched nothing —
  including the `firstOrNull` / `firstVisibleOrNull` no-match paths and
  nested eager loads with no parent groups — matching `explain()` and the
  existing relationship-empty / `limit(0)` behavior. Driver fetches are
  still skipped when nothing could match. An interceptor that `reject()`s
  on `EAGER_LOAD` now rejects such reads: `firstOrNull` throws
  `EntQueryRejectedException` instead of returning null, `firstOrError`
  returns `Err(QueryRejected)` instead of `Err(NotFound)`, and
  `allOrThrow` on an empty root throws instead of returning `[]`.
  _Migration:_ interceptors that reject on `EAGER_LOAD` must tolerate
  empty-batch passes (or callers must expect the rejection on empty
  results); observing interceptors see one additional `EAGER_LOAD` pass
  per configured edge on empty reads.

- **`queryX()` traversal follows the source query's shape; two new sealed `Predicate` bridges** (`schema`, `codegen`, `postgres`, `runtime`)
  Edge traversal no longer drops source `orderBy` / `limit` / `offset` at the
  bridge: generated `queryX()` embeds the post-interceptor source shape in the
  new sealed `Predicate` subclasses `HasEdgeFromShape` / `HasM2MEdgeFromShape`
  (carrying `entkt.query.TraversalSourceShape`), lowered as a source-id
  subquery, and interceptor limit operations now apply to
  `ReadOperation.EDGE_TRAVERSAL` instead of silently no-oping (see the
  [shape-preserving traversal note](../implemented-features/query/edge-traversal-source-shape.md)).
  _Migration:_ traversal results can narrow — drop source bounds that the old
  lowering silently ignored if the broad row set was actually intended; add
  branches for the two new subclasses to any exhaustive `when` over
  `Predicate`; custom drivers must lower the shaped bridges (silently falling
  back to predicate-only traversal is not allowed).

- **`QueryFlag` moved from `entkt.runtime.query` to `entkt.query`** (`runtime`, `schema`)
  The enum now lives in the schema module so `TraversalSourceShape` can carry
  the source step's flags without `:schema` depending on `:runtime`.
  _Migration:_ update imports to `entkt.query.QueryFlag`.

- **Privacy-rule and validator contexts expose a read-only `EntReadClient`** (`codegen`, `runtime`)
  `ctx.client` on the generated privacy rule contexts (LOAD / CREATE /
  UPDATE / DELETE) and validator contexts is now the read-only
  `EntReadClient` instead of the full `EntClient`: writes, transactions,
  and re-scoping no longer compile from rule or validator code. The two
  surfaces deliberately differ in posture: privacy-rule readers are
  **viewer-scoped** — rule reads see what the viewer being authorized can
  see, and raw terminals (`rawCount` / `rawExists` / raw aggregates,
  including the `*OrError` variants) throw `IllegalStateException` at
  runtime — while validator readers are **privacy-bypass-scoped** —
  validation reads see all rows and raw terminals keep working. Hook
  contexts keep the full `EntClient`. See the
  [read-only privacy client note](../implemented-features/privacy-validation/read-only-privacy-client.md).
  _Migration:_ retype rule/validator helper signatures from `EntClient` to
  `EntReadClient`; in privacy rules, replace privacy-bypassing loads with
  LOAD-checked reads (`byIdOrNull` throws on a denied row) or the
  `visible*` family (a denied row collapses to null/absent); move writes
  out of rules and validators into hooks or callers.

- **`io.entkt:ent-viewer` renamed to `io.entkt:ent-viewer-core`** (`ent-viewer`)
  The viewer family now lives under one `ent-viewer/` folder as
  `ent-viewer-core` (framework-neutral) and `ent-viewer-spring` (Spring Boot
  auto-configuration that mounts an application-declared `EntViewer` bean).
  Kotlin packages are unchanged (`entkt.viewer`, `entkt.viewer.spring`).
  _Migration:_ change the dependency coordinate to
  `io.entkt:ent-viewer-core`; Spring Boot apps can add
  `io.entkt:ent-viewer-spring` and delete their hand-written mount.

- **`ColumnMetadata` gains `sensitive: Boolean = false` (positional shift)** (`runtime`)
  `.sensitive()` now flows into runtime column metadata (it powers the ent
  viewer's redaction and is a framework-wide display contract). The new
  parameter sits between `comment` and `default`, so positional construction
  past `comment` shifts; named-argument construction (the norm) is
  unaffected.
  _Migration:_ use named arguments for hand-built `ColumnMetadata`.

- **Typed JSON is mapper-pluggable: `JsonColumnMetadata` reshaped, `PostgresDriver(json)` replaced** (`runtime`, `postgres`, `codegen`)
  JSON columns flow through a driver-level `JsonColumnCodec` (kotlinx
  default; Jackson via the new `io.entkt:jackson` module, selected at
  codegen time with `entkt { jsonMapper }`). `JsonColumnMetadata(klass,
  serializer, typeName)` became `JsonColumnMetadata(klass, kType, typeName,
  mapper, kotlinxSerializer?)`, and `PostgresDriver`'s `json:
  Json` constructor parameter is now `jsonCodec: JsonColumnCodec`. See
  [Pluggable JSON Mappers](../implemented-features/schema/pluggable-json-mappers.md).
  _Migration:_ regenerate code (the `SCHEMA` literal shape changed); replace
  `PostgresDriver(ds, json = Json {...})` with
  `PostgresDriver(ds, jsonCodec = KotlinxJsonCodec(Json {...}))`; hand-built
  metadata adds `kType = typeOf<T>()` and `mapper = JsonMapperIds.KOTLINX`,
  renames `serializer` to `kotlinxSerializer`, and must now supply
  `typeName` (previously optional, now required for diagnostics).

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
