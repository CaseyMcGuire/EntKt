# Implementation Plan — Phase 1: Postgres `pgvector`

Execution plan for [native-database-column-types.md](native-database-column-types.md)
§"Phase 1: Postgres `pgvector`". The RFC is the contract (what/why); this is the
order (how). Same discipline as the RFC 10 plan: **each phase is independently
buildable, green, and committed to `master`** (explicit-pathspec commits), with
`./gradlew test` run before each commit.

## Context

`pgvector` is one concrete native field type. The work mirrors the shipped `enum()`
pipeline hop-for-hop (see the RFC's Appendix), adding the minimal shared foundation
a native type forces: a `ColumnStorage` carrier, native-index metadata, a
`supportsNativeStorage` driver capability, and storage-keyed bind/decode.

**The one cross-cutting hazard** is adding `FieldType.PGVECTOR` to a *closed* enum:
it breaks every exhaustive `when (FieldType)`. That is *intended* (the compiler then
lists every path that must handle it), but it means the value can't be added "for
free." Strategy: Phase 1 introduces everything that does **not** touch `FieldType`;
Phase 2 adds the enum value and triages all match sites at once (real handling where
cheap, a clear `error("pgvector lands in Phase N")` placeholder otherwise), so the
build stays green; Phases 3–6 replace placeholders with real behavior.

Exhaustive `when (FieldType)` / `FieldType.X` sites that Phase 2 must touch:
`codegen/.../TypeMappings.kt:9` (`toTypeName`) and `:27` (`resolvedTypeName`),
`codegen/.../{EntityGenerator,CreateGenerator,UpdateGenerator,SchemaMetadata}.kt`,
`postgres/.../PostgresTypeMapper.kt:29` (`sqlTypeFor`), `postgres/.../PostgresDriver.kt:820`
(`bind`) + its decode path, `migrations/.../NormalizedSchema.kt`, `schema/.../query/Column.kt`.

---

## Phase 1 — Runtime `PgVector` + additive metadata carriers

Purely additive; **no `FieldType` change**, so nothing exhaustive breaks. Fully green.

- `postgres/.../runtime/PgVector.kt` (new): the value type from RFC §7 — content
  `equals`/`hashCode`, `dimensions`, `of(FloatArray)`/`of(List<Float>)`, **private
  ctor + defensive copy in (`of`) and out (`toFloatArray()`), read-only `get(i)`** so
  the backing array is never shared (mutation can't destabilize identity).
- `schema/.../ColumnStorage.kt` (new): `sealed interface ColumnStorage { data class
  Native(dialect, typeName, sqlType, codec, requiredExtension, dimensions) }` (RFC §5).
- `schema/.../Field.kt`: add `storage: ColumnStorage? = null` (defaulted → additive).
- `runtime/.../EntitySchema.kt`: add `ColumnMetadata.storage: ColumnStorage? = null`;
  add `IndexMetadata.using: String? = null` + `opclasses: List<String>? = null` +
  `with: Map<String, String>? = null` (RFC §6 — `with` carries IVFFlat `lists`).
- `runtime/.../Driver.kt`: `fun supportsNativeStorage(codec: String): Boolean = false`
  (default; NoopDriver inherits).

**Tests:** `PgVector` unit (content equality across instances; `of` overloads;
`dimensions`); a metadata test that the new fields default to `null` and existing
`ColumnMetadata`/`IndexMetadata` literals are unaffected.

**Green:** trivially — no enum change, all new fields defaulted.

## Phase 2 — Schema DSL: `FieldType.PGVECTOR` + `postgresVector` + exhaustive-site triage

- `schema/.../FieldType.kt`: add `PGVECTOR`.
- `schema/.../FieldBuilder.kt`: add a `@PublishedApi internal fun setNativeStorage(s:
  ColumnStorage)` (mirrors `setEnumClass`), and in the **final** `build()` fold
  `storage` into `Field` **and** reject incompatible modifiers on a native column
  (`if (storage is ColumnStorage.Native && unique) error("Field '$fieldName' is a
  native ${storage.typeName} column; .unique() is not supported")`). `build()` is final
  — do **not** override it (see RFC §2/§3).
- `schema/.../PgVectorFieldBuilder.kt` (new): internal ctor, `FieldType.PGVECTOR`, **no
  declared modifiers and no `build()` override** — `.nullable()`/`.comment()` are
  inherited; `.unique()`/`.immutable()`/`.sensitive()` are inherited too (the first is
  rejected at `build()`, the rest tolerated); `.default()`/`.minLength()`/`.maxLength()`
  are subclass-only so simply absent.
- `schema/.../EntSchema.kt` registration: `@PublishedApi internal fun
  EntSchema.registerPostgresVector(name, dimensions)` mirroring `enum(name, KClass)`
  (`require(dimensions in 1..16000)`, validateName, checkNotFinalized, `setNativeStorage(
  Native("postgres","vector","vector($dimensions)","postgres.vector","vector",dimensions))`,
  declarationOwner, `_fields.add`).
- `schema/.../postgres/vector/Dsl.kt` (new, package `entkt.postgres.vector`, physically
  in the schema module): `inline fun EntSchema.postgresVector(name, dimensions) =
  registerPostgresVector(...)`.
- **Triage every exhaustive `FieldType` site** (listed in Context) to keep the build
  green: `toTypeName`/`sqlTypeFor`/`bind`/decode/`NormalizedSchema` get a
  `FieldType.PGVECTOR -> error("pgvector <layer> lands in Phase 3/4/5")` branch (real
  mapping deferred); cheap real branches are fine where obvious.

**Tests (schema):** `postgresVector("e", 1536)` builds `Field(type = PGVECTOR,
storage = Native(...))`; `dimensions = 0`/`16001` throw at declaration;
`postgresVector(...).unique()` throws at `build()`/finalize with the field-named
message; `postgresVector(...).maxLength(…)` does not compile (subclass-only modifier
absent).

**Green:** yes — placeholders satisfy the now-exhaustive matches.

## Phase 3 — Codegen: `PgVector` property + storage threading + write/decode + setter check

- `codegen/.../TypeMappings.kt`: `FieldType.PGVECTOR -> ClassName("entkt.postgres.runtime",
  "PgVector")` in `toTypeName`; handle it in `resolvedTypeName` (alongside the enum branch).
- `codegen/.../SchemaMetadata.kt` (`:104-243`, `:421-531`): copy `field.storage` →
  `ColumnDescriptor` → emit `ColumnMetadata(storage = ColumnStorage.Native(...))` literal.
- Entity / create / update generators: property `PgVector?`; `fromRow` passes the
  driver value through (driver owns decode — §8); write map passes `PgVector`
  straight through; generated `create`/`update` setter emits the dimension check
  (`require(value.dimensions == <n>) { "<entity>.<field> expects vector(<n>)..." }`).
- Replace the Phase-2 codegen placeholder branches with the real mapping.

**Tests (codegen):** generated entity/create/update expose `PgVector`; the emitted
`ColumnMetadata` literal carries `storage`; the setter emits the dimension `require`;
a non-vector schema is byte-identical to before.

## Phase 4 — Postgres driver: bind / decode / capability / register-reject / column DDL

- `postgres/.../PostgresDriver.kt`: in `bind` (`:820`), look up the column's storage
  via `schemaFor(table).columns.first { it.name == col }.storage`; if `Native` with
  codec `"postgres.vector"`, dimension-check then bind a `PGobject(type = "vector",
  value = "[f0,f1,...]")`. Mirror in the decode path. `supportsNativeStorage("postgres.vector")
  = true` on the root **and** the transactional sub-driver. In `register`, reject any
  `ColumnStorage.Native` whose codec is unsupported with `UnsupportedDriverCapabilityException`.
- **`TypeMapper.sqlTypeFor` signature change** (`migrations/.../Interfaces.kt:16`):
  today `sqlTypeFor(fieldType, isPrimaryKey, idStrategy)` gets no storage, and
  `NormalizedSchema.kt:31` calls it with `col.type` only — so it cannot return
  `vector(1536)`. Add a defaulted `storage: ColumnStorage? = null` param
  (backward-compatible for every existing mapper/caller); `NormalizedSchema` passes
  `col.storage`; `PostgresTypeMapper.kt:29` returns `storage.sqlType` for `PGVECTOR`.
- Replace the Phase-2 driver placeholders.

**Tests (postgres integration, testcontainer):** round-trip a `PgVector` through
create + query (nullable and non-null); content equality after hydrate; a
wrong-dimension value is rejected at bind; a vector schema registered on a
non-supporting driver throws `UnsupportedDriverCapabilityException` at `register`
(de-risk #1 — write this first).

## Phase 5 — Migrations + vector index DSL

- **Schema-side index carrier** (`schema/.../Index.kt:3`, today `name/fields/unique/where`):
  add `using`/`opclasses`/`with` (nullable → btree unchanged). `schema/.../IndexBuilder.kt`
  gains a `@PublishedApi internal setVectorIndex(using, opclasses, with)` (mirrors
  `setNativeStorage`), folded into the built `Index` by the final `build()`.
- **Index registration bridge** (same blocker as the field hook — see RFC §6): the
  `IndexBuilder` ctor is `internal`, `index()` is `protected` and does column-ownership
  validation (`EntSchema.kt:240-256`), `_indexes` is `internal`. Add `@PublishedApi
  internal fun EntSchema.registerPostgresVectorIndex(name, field)` (replicates the
  ownership check, builds the `IndexBuilder`, `_indexes.add`); the public `inline
  postgresVectorIndex` + `inline .hnsw(VectorMetric.Cosine)` / `.ivfflat(metric, lists = N)`
  (calling `setVectorIndex`) live in `schema/.../postgres/vector/Dsl.kt`. `VectorMetric`
  enum + opclass map (RFC §6). Produces a schema `Index` with the fields set
  (`with = {"lists":"100"}` for IVFFlat).
- **Codegen threading:** `codegen/.../SchemaMetadata.kt:501` (`schema.indexes()`) copies
  `using`/`opclasses`/`with` from the schema `Index` into the emitted runtime
  `IndexMetadata`.
- `migrations/.../NormalizedSchema.kt`: carry `storage` on `NormalizedColumn`; copy
  `using`/`opclasses`/`with` into `NormalizedIndex` (`:111`, today has none). In
  `SchemaDiffer`, fold `using`/`opclasses`/`with` into the index identity `IndexKey`
  (`:141`, today `(columns, unique, where)`) so a `btree→hnsw`/opclass/`lists` change is
  a detected drop+recreate; classify a `storage.dimensions` change as
  **manual/destructive** (reuse the existing type-change-defers-to-manual machinery).
- **`CREATE EXTENSION` as a first-class ordered op** (not loose SQL):
  `migrations/.../MigrationOp.kt` add `data class CreateExtension(val name: String)`;
  `SchemaDiffer.sortOps` (`:251`) give it priority **−1** (before `CreateTable(0)`); the
  differ emits one `CreateExtension("vector")` (deduped) when any column carries
  `requiredExtension = "vector"`. Without this, a `vector(n)` column can be created
  before the extension exists.
- `postgres/.../PostgresSqlRenderer.kt`: render `CreateExtension` →
  `CREATE EXTENSION IF NOT EXISTS <name>`; extend `renderAddIndex` (`:83`) to render
  `USING <method> (<col> <opclass>)` + optional `WITH (lists = N)`.
- **`autoDdl` runtime path** (`PostgresDriver.register`, `:72-81`, which builds DDL
  directly — *not* via the differ): for a schema with a column carrying
  `requiredExtension`, emit `CREATE EXTENSION IF NOT EXISTS <ext>` **before**
  `createTableSql`, and render the vector index via the same `using`/`opclasses`/`with`
  path. Else `autoDdl` creates `vector(n)` before the extension exists.
- `postgres/.../PostgresIntrospector.kt`: read a live `vector(n)` column + HNSW/IVFFlat
  index (via `pg_attribute`/`pg_type`/`pg_index`/`pg_opclass`) back into
  `ColumnStorage.Native` / `IndexMetadata`. **DEFERRED** (follow-up): the autoDdl path
  (`CREATE … IF NOT EXISTS`) is idempotent and unaffected; the only gap is the
  *migration-file* differ, which without pgvector read-back sees a live vector index as
  "missing" and re-emits an idempotent `AddIndex`. Not data-affecting; tracked separately.

**Tests (migrations + postgres):** `vector(n)` column DDL; `CREATE EXTENSION` emitted
once and ordered before `CREATE TABLE`; HNSW and IVFFlat index DDL with the right
opclass/`WITH (lists = N)`; a `vector(1536) → vector(3072)` change classified
manual/destructive; an unchanged vector schema emits no ops; a real autoDdl run builds
the extension + vector column + hnsw index on pgvector.

## Phase 6 — Query: nearest-neighbor distance ordering

- **New order model** (RFC §10). `OrderField` is `(field, direction)` only
  (`schema/.../query/OrderField.kt:9`) and the driver renders `alias.field DIR`
  (`PostgresDriver.kt:295`) — no room for a distance expression. Generalize the
  order-by element to a sealed `OrderExpression`: `Column(field, dir)` (the existing
  case, kept byte-identical) and `NativeDistance(field, operator, operand, dir)`.
  `OrderField` becomes an alias of / is replaced by `OrderExpression.Column` (mechanical
  — every existing `orderBy` yields `Column`).
- Generated distance helpers on vector fields only: `cosineDistance(q)` /
  `l2Distance(q)` / `innerProduct(q)` build an `OrderExpression.NativeDistance` whose
  `operator` is a **closed `VectorDistanceOperator` enum** (`L2`/`Cosine`/`NegInnerProduct`,
  each carrying its `sql`) — never a raw String, and the `NativeDistance` ctor is
  `internal` (only these helpers build it), so no caller text reaches SQL. `innerProduct`
  lowers to `<#>` (pgvector's **negative** inner product); its generated KDoc documents
  that `.asc()` = most-similar-first.
- Driver ORDER BY renderer switches on the variant: `Column` keeps `alias.field DIR`;
  `NativeDistance` renders `alias.field <=> ? DIR` and **binds the operand `PgVector` as
  a parameter** (never inlined into SQL). Gated by
  `supportsNativeStorage("postgres.vector")` — a non-Postgres driver rejects a
  `NativeDistance` element at lowering with a capability error.
- Touches the query AST (`schema/.../query/OrderField.kt`), `codegen` (helper
  generation + threading the new order type), and `postgres` (the ORDER BY renderer +
  param binding).

**Tests:** `orderBy(embedding.cosineDistance(q).asc()).limit(k)` returns rows in
ascending distance order with the operand bound as a parameter; an existing scalar
`orderBy` is unchanged (`Column` path); the helper on a non-Postgres driver fails with a
capability error.

**Tests (postgres integration):** `orderBy(embedding.cosineDistance(q).asc()).limit(k)`
returns rows in ascending distance order; the same helper on a non-Postgres driver
fails with a capability error.

---

## Key risks (de-risk first)

1. **`FieldType` enum breakage (Phase 2).** Adding `PGVECTOR` breaks every exhaustive
   `when`. Mitigate by triaging *all* sites in Phase 2 with placeholders so the build
   never goes red; pin the site list (Context) and grep for `FieldType.PGVECTOR` to
   confirm none was missed.
2. **`bind` lacks storage context (Phase 4).** `bind(stmt, idx, type: FieldType?,
   value)` doesn't get `ColumnStorage` today. Resolve by looking the column up from
   the already-registered `schemaFor(table)` (the same source `columnTypeOf` uses) —
   no public `Driver` API change. Write the round-trip + register-reject tests first.
3. **PGobject vector encoding.** The `vector` wire format is `"[f0,f1,...]"`. Pin the
   exact float formatting (no scientific notation surprises) with a round-trip test
   before building higher layers.
4. **Dimension-change classification (Phase 5).** Must hook the *existing* diff
   machinery, not a parallel path. Confirm by reading how a scalar type change is
   currently classified and extend that comparison to `storage.dimensions`.
5. **Module direction.** `schema` must not depend on `postgres` (`PgVector` lives in
   the postgres runtime; the builder's value param is `FloatArray`). Keep
   `ColumnStorage` in `schema` (runtime already depends on schema). Verify no
   `schema → postgres` import sneaks in.

## Compatibility check (per phase)

Existing non-vector schemas must generate byte-identical code and DDL throughout:
every added field is nullable/defaulted, `IndexMetadata.using == null` keeps btree
indexes unchanged, and the `FieldType.PGVECTOR` branch is never reached for them.
Assert this with an existing-fixture golden comparison after Phases 3 and 5.

## Verification

- Per phase: full `./gradlew test` green (incl. Postgres testcontainer + example-spring
  + integration-tests) before committing.
- End-to-end (after Phase 6): an `Article(embedding: PgVector?)` integration fixture
  proves create/query round-trip, nullable handling, HNSW index DDL + `CREATE
  EXTENSION`, dimension-mismatch rejection, non-Postgres `register` rejection, and
  nearest-neighbor ordering — i.e. the RFC §"Test Requirements (Phase 1)" list.
