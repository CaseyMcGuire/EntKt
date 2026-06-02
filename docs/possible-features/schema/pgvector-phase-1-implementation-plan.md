# Implementation Plan — Phase 1: Postgres `pgvector`

Execution plan for [custom-field-types-converters.md](custom-field-types-converters.md)
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

- `postgres/.../runtime/PgVector.kt` (new): the value type from RFC §7 — `class
  PgVector(val values: FloatArray)` with content `equals`/`hashCode`, `dimensions`,
  `of(FloatArray)` / `of(List<Float>)`.
- `schema/.../ColumnStorage.kt` (new): `sealed interface ColumnStorage { data class
  Native(dialect, typeName, sqlType, codec, requiredExtension, dimensions) }` (RFC §5).
- `schema/.../Field.kt`: add `storage: ColumnStorage? = null` (defaulted → additive).
- `runtime/.../EntitySchema.kt`: add `ColumnMetadata.storage: ColumnStorage? = null`;
  add `IndexMetadata.using: String? = null` + `opclasses: List<String>? = null` (RFC §6).
- `runtime/.../Driver.kt`: `fun supportsNativeStorage(codec: String): Boolean = false`
  (default; NoopDriver inherits).

**Tests:** `PgVector` unit (content equality across instances; `of` overloads;
`dimensions`); a metadata test that the new fields default to `null` and existing
`ColumnMetadata`/`IndexMetadata` literals are unaffected.

**Green:** trivially — no enum change, all new fields defaulted.

## Phase 2 — Schema DSL: `FieldType.PGVECTOR` + `postgresVector` + exhaustive-site triage

- `schema/.../FieldType.kt`: add `PGVECTOR`.
- `schema/.../PgVectorFieldBuilder.kt` (new): internal ctor, `FieldType.PGVECTOR`,
  exposes only `.nullable()` / `.comment()`; `build()` sets `Field.storage =
  ColumnStorage.Native("postgres", "vector", "vector($dimensions)", "postgres.vector",
  "vector", dimensions)`.
- `schema/.../EntSchema.kt`: `@PublishedApi internal fun EntSchema.registerPostgresVector(name, dimensions)`
  mirroring `enum(name, KClass)` (validateName, checkNotFinalized, declarationOwner,
  `_fields.add`), with `require(dimensions in 1..2000)`.
- `schema/.../postgres/vector/Dsl.kt` (new, package `entkt.postgres.vector`, physically
  in the schema module): `inline fun EntSchema.postgresVector(name, dimensions) =
  registerPostgresVector(...)`.
- **Triage every exhaustive `FieldType` site** (listed in Context) to keep the build
  green: `toTypeName`/`sqlTypeFor`/`bind`/decode/`NormalizedSchema` get a
  `FieldType.PGVECTOR -> error("pgvector <layer> lands in Phase 3/4/5")` branch (real
  mapping deferred); cheap real branches are fine where obvious.

**Tests (schema):** `postgresVector("e", 1536)` builds `Field(type = PGVECTOR,
storage = Native(...))`; `dimensions = 0` and `= 2001` throw at declaration; the
restricted modifier surface (a `postgresVector(...).unique()` does not compile —
assert via a compile-fail fixture, or reflectively that the method is absent).

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
- `postgres/.../PostgresTypeMapper.kt:29`: `FieldType.PGVECTOR -> storage.sqlType`
  (the mapper must receive storage; thread `ColumnMetadata`/storage into `sqlTypeFor`
  or special-case before the `when`).
- Replace the Phase-2 driver placeholders.

**Tests (postgres integration, testcontainer):** round-trip a `PgVector` through
create + query (nullable and non-null); content equality after hydrate; a
wrong-dimension value is rejected at bind; a vector schema registered on a
non-supporting driver throws `UnsupportedDriverCapabilityException` at `register`
(de-risk #1 — write this first).

## Phase 5 — Migrations + vector index DSL

- `schema/.../postgres/vector/Dsl.kt`: `postgresVectorIndex(name, field)` builder with
  `.hnsw(VectorMetric.Cosine)` / `.ivfflat(metric, lists = N)`, producing
  `IndexMetadata(using = "hnsw", opclasses = ["vector_cosine_ops"])`; `VectorMetric`
  enum + opclass map (RFC §6). Registered like `index(...)`.
- `migrations/.../NormalizedSchema.kt`: carry `storage` on the normalized column and
  `using`/`opclasses` on the normalized index; in the diff, classify a
  `storage.dimensions` change as **manual/destructive** (reuse the existing
  type-change-defers-to-manual machinery).
- `postgres/.../PostgresSqlRenderer.kt` (`renderAddIndex` `:83`): render `USING <method>
  (<col> <opclass>)`; emit `CREATE EXTENSION IF NOT EXISTS vector` once when any
  column in the migration carries `requiredExtension = "vector"`.
- `postgres/.../PostgresIntrospector.kt`: read a live `vector(n)` column + HNSW/IVFFlat
  index (via `pg_attribute`/`pg_type`/`pg_index`/`pg_opclass`) back into
  `ColumnStorage.Native` / `IndexMetadata`.

**Tests (migrations + postgres):** `vector(n)` column DDL; `CREATE EXTENSION` emitted
once; HNSW and IVFFlat index DDL with the right opclass/`WITH (lists = N)`; a
`vector(1536) → vector(3072)` change classified manual/destructive (de-risk #2);
introspection round-trips a created vector column + index.

## Phase 6 — Query: nearest-neighbor distance ordering

- Generated distance helpers on vector fields only: `cosineDistance(q)` /
  `l2Distance(q)` / `innerProduct(q)` → order-by expressions lowering to `<=>` /
  `<->` / `<#>`; gated by `supportsNativeStorage("postgres.vector")` so a
  non-Postgres driver fails with a capability error rather than invalid SQL.
- Touches `codegen` (helper generation) + `postgres` (operator lowering) + the query
  AST (`schema/.../query/Column.kt` order-by surface).

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
