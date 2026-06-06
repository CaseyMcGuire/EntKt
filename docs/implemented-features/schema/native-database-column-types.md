# RFC: Native Database Column Types

## Status

**Implemented** (Postgres `pgvector`). The sections below are the original
implementation contract, kept as a design record. User-facing docs are
[Schema -> Native Column Types](../../02-schema.md#native-column-types-postgres-pgvector)
and [Queries -> Vector distance ordering](../../04-queries.md#vector-distance-ordering-pgvector).

### As-built deltas from the contract

The implementation followed this contract closely, with these intentional changes:

- **`PgVector` package.** The value type ships in `entkt.postgres.vector` (alongside
  the `postgresVector` DSL and distance helpers), not `entkt.postgres.runtime` as §7
  sketches -- so a single `import entkt.postgres.vector.*` brings the whole surface.
- **Order model (§10).** Rather than a sealed `OrderExpression`, `OrderField` gained
  an optional `distance: DistanceOrder?` (additive, backward-compatible). The closed
  `VectorDistanceOperator` enum + parameter-bound operand are as designed, and the
  distance helpers are import-gated extensions on `Column<E, PgVector>`, so no codegen
  change was needed. Distance ordering renders `NULLS LAST` -- a null embedding has no
  distance and sorts last in both directions.
- **Validation surface (§4) is wider.** Dimensions are validated not only at
  declaration / generated write, but also at raw `driver.insert/update`, at query
  predicates (`eq`/`neq`/`in`/`notIn`), and at the distance-query operand -- all with
  field-named errors. `PgVector.of` rejects non-finite (`NaN`/`Infinity`) components.
  Vector indexes are validated at declaration: a non-vector column, `dimensions > 2000`,
  `ivfflat lists <= 0`, `.unique()`, or a missing access method are all rejected.
- **Introspection (§5 hop table) is implemented.** `PostgresIntrospector` reconstructs
  `vector(n)` via `format_type` and reads a non-btree index's access method, operator
  classes, and storage params, so a pgvector schema round-trips with no spurious drift.
- **Flyway shadow image.** The shadow workflow defaults to a pgvector-capable image
  and preflights required extensions, so vector schemas apply in the shadow DB.

## Summary

Allow schema fields to opt into explicit database-native column types without
making the base DSL pretend those types are portable. Postgres `pgvector` is the
first concrete type and drives the minimal shared foundation: a `ColumnStorage`
carrier, driver capability checks, native-index metadata, storage-aware migrations,
and storage-keyed bind/decode.

The codebase already ships the proof that this shape works: typed `enum` fields
(`EntSchema.enum<E>(name)`) expose a Kotlin enum at the API boundary while storing
a DB value. `pgvector` follows that pipeline exactly — see *Appendix: the `enum`
blueprint*.

## Scope

This RFC covers Postgres `pgvector` only:

- `postgresVector("embedding", dimensions = N)` schema field → generated `PgVector`
  property.
- `postgresVectorIndex(name, field).hnsw(VectorMetric.Cosine)` (and `.ivfflat(...)`).
- Bind/decode round-trip in `PostgresDriver`; `vector(N)` DDL; `CREATE EXTENSION IF
  NOT EXISTS vector`; HNSW/IVFFlat index DDL.
- Nearest-neighbor **ordering** query surface (`cosineDistance` / `l2Distance` /
  `innerProduct` in `ORDER BY`).
- Non-Postgres drivers reject the schema at `register()`.
- The minimal shared foundation Phase 1 *requires*: a `ColumnStorage` carrier on
  `Field`/`ColumnMetadata`, native-index metadata on `IndexMetadata`, a
  `supportsNativeStorage(codec)` driver capability, and storage-keyed bind/decode.

Out of scope here:

- Custom scalar converters (`Email`, `Slug`): see
  [Custom Scalar Converters](../../possible-features/schema/custom-scalar-converters.md).
- Typed JSON fields (`json("pet_metadata", PetMetadata::class)`): see
  [Typed JSON Fields](../../possible-features/schema/typed-json-fields.md).
- Arrays, native database enums, and vector filtering. Each needs its own design
  note before implementation.

Phase 1 deliberately does **not** retrofit the existing `enum` feature onto
`ColumnStorage`. `enum` keeps its `FieldType.ENUM` + `Field.enumClass` path; unifying
the two is a separate, behavior-preserving refactor.

## Non-Goals

- No full serialization-framework dependency.
- No dynamically-typed fields; no arbitrary polymorphic conversion.
- No bypassing field validation.
- No requiring every driver to support every storage type.
- No hiding dialect-specific behavior behind portable-looking APIs. A Postgres-native
  field looks Postgres-native at the schema boundary (it is brought in with
  `import entkt.postgres.vector.*`, not present on the base DSL).

---

## Phase 1: Postgres `pgvector`

### 1. Schema API

```kotlin
import entkt.postgres.vector.*   // postgresVector, postgresVectorIndex, VectorMetric

class Article : EntSchema("articles") {
    val title = text("title")

    val embedding = postgresVector("embedding", dimensions = 1536)
        .nullable()

    // Vector indexes are declared like any other index (a `val` on the schema),
    // but spell out the access method + metric — they are not btree.
    val embeddingHnsw = postgresVectorIndex("idx_articles_embedding_hnsw", embedding)
        .hnsw(VectorMetric.Cosine)
}
```

`postgresVector` and `postgresVectorIndex` are **not** members of `EntSchema`. They
are import-gated extensions (see §2), so a schema that never touches Postgres never
sees them — honoring "look Postgres-native".

### 2. The schema registration hook (resolves "registration is private")

The blocker the reviewer identified is real: `EntSchema.registerField` is `private`
(`EntSchema.kt:134`) and `FieldBuilder`'s constructor is `internal`
(`FieldBuilder.kt:4`), so no external code can construct or register a field builder.
**The `enum` feature already solved exactly this** and is the template:

```kotlin
// EntSchema.kt:170-181 (existing) — the bridge we mirror
protected inline fun <reified E : Enum<E>> enum(name: String): EnumFieldBuilder =
    enum(name, E::class)

@PublishedApi
internal fun enum(name: String, enumClass: KClass<out Enum<*>>): EnumFieldBuilder =
    EnumFieldBuilder(name).also {
        validateName(name, "Field"); checkNotFinalized()
        it.setEnumClass(enumClass); it.declarationOwner = this; _fields.add(it)
    }
```

A `public`/`inline` entry calls a `@PublishedApi internal` registration function that
does the real work against the `internal _fields` list. `@PublishedApi internal` is
JVM-public, so inlined call sites in *user* modules reach it. Phase 1 adds the same
two pieces, **all inside the `schema` module** (an inline function may only reference
`@PublishedApi internal` members of its *own* module, so the public entry lives in
`schema`, under a Postgres-flavored package):

`FieldBuilder.build()` is **final** (`FieldBuilder.kt:61`) and per-type state is
attached the way `enum` does it: a `@PublishedApi internal` setter the registration
calls, which the final base `build()` folds into the emitted `Field` (`enum` uses
`setEnumClass`, read by `build()`; `FieldBuilder.kt:55-93`). Phase 1 mirrors that —
the builder carries no overridden `build()`, only the FK to a `setNativeStorage`:

```kotlin
// schema/src/main/kotlin/entkt/schema/PgVectorFieldBuilder.kt  (internal ctor, like every builder)
// Carries no extra state: dimensions/storage are set via setNativeStorage below.
// .nullable()/.comment() are inherited from the base; no build() override (build is final).
class PgVectorFieldBuilder internal constructor(name: String) :
    FieldBuilder<PgVectorFieldBuilder, FloatArray>(name, FieldType.PGVECTOR)

// schema/src/main/kotlin/entkt/schema/FieldBuilder.kt  (ADD — mirrors setEnumClass)
@PublishedApi internal fun setNativeStorage(s: ColumnStorage) { this.storage = s }
// ...and the final build() folds `storage` into Field, plus rejects invalid
// modifiers for native columns (see §3): e.g.
//   if (storage is ColumnStorage.Native && unique)
//       error("Field '$fieldName' is a native ${storage.typeName} column; .unique() is not supported")

// schema module, registration function — mirrors enum(name, KClass)
@PublishedApi
internal fun EntSchema.registerPostgresVector(name: String, dimensions: Int): PgVectorFieldBuilder {
    require(dimensions in 1..16000) { "postgresVector('$name') dimensions must be 1..16000, got $dimensions" }
    return PgVectorFieldBuilder(name).also {
        validateName(name, "Field"); checkNotFinalized()
        it.setNativeStorage(ColumnStorage.Native(
            dialect = "postgres", typeName = "vector", sqlType = "vector($dimensions)",
            codec = "postgres.vector", requiredExtension = "vector", dimensions = dimensions,
        ))
        it.declarationOwner = this; _fields.add(it)
    }
}

// schema/src/main/kotlin/entkt/postgres/vector/Dsl.kt  (package entkt.postgres.vector; physically in schema module)
inline fun EntSchema.postgresVector(name: String, dimensions: Int): PgVectorFieldBuilder =
    registerPostgresVector(name, dimensions)
```

This needs **no** change to `registerField`'s privacy or `FieldBuilder`'s `internal`
ctor, and does **not** override the final `build()` — it reuses the `enum`
`setEnumClass` + final-`build()` pattern verbatim. The base `build()` gains: fold
`storage` into `Field`, and one storage-aware modifier check (§3). The runtime
`PgVector` value type and the driver codec live in the **postgres** module (§7); the
schema module never references them (`PgVectorFieldBuilder`'s value param is
`FloatArray`, not `PgVector`), so there is no `schema → postgres` dependency.

> **Decision:** Phase 1 keeps the *builder* in the `schema` module (forced by the
> `internal` ctor) but gates the *DSL entry* behind `import entkt.postgres.vector.*`.
> Letting an external module subclass `FieldBuilder` directly (a true plugin SPI) is a
> Phase-2+ concern; it would require opening the `FieldBuilder` ctor to `protected`
> and is not needed for any Phase-1 native type.

### 3. Field modifiers (resolves "inherited modifiers need rules")

Enforcement is split, because the modifier surface is split in the current builder
hierarchy:

- **Subclass-only modifiers are absent at compile time.** `.default(...)`,
  `.minLength()`, `.maxLength()`, `.defaultNow()` etc. live on the *concrete* scalar
  builders (`StringFieldBuilder`, `EnumFieldBuilder`, …), not the base. Since
  `PgVectorFieldBuilder` declares none of them, calling `postgresVector(...).maxLength(…)`
  simply does not compile. Good — these are the genuinely length/scalar-specific ones.
- **Base modifiers are inherited and cannot be removed, so they are checked at
  `build()`.** `.nullable()`, `.unique()`, `.immutable()`, `.sensitive()`, `.comment()`
  are `public` on the base `FieldBuilder` (`FieldBuilder.kt:48-53`) and inherited by
  *every* builder — there is no way to make `.unique()` a compile error on a subclass
  without a base-class refactor. And `build()` is `final` — it is a plain `fun build()`
  (`FieldBuilder.kt:61`), and Kotlin methods are final unless marked `open`, so a
  subclass cannot override it. So the one genuinely-broken modifier is rejected in the
  final `build()` with a clear,
  field-named error (the same place `build()` already rejects `immutable ⊕ updateDefault`
  and non-finite defaults).

| Modifier | On `postgresVector`? | Enforcement |
| --- | --- | --- |
| `.nullable()` | **yes** | inherited; `vector` is nullable in Postgres, round-trips null. |
| `.comment(...)` | **yes** | inherited; plain column comment. |
| `.unique()` | **no** | inherited but **rejected at `build()`**: a `UNIQUE` index over a high-dim vector is broken. Error: `Field 'embedding' is a native vector column; .unique() is not supported`. |
| `.immutable()` / `.sensitive()` | tolerated | inherited and harmless on a vector; accepted (not worth a special error). |
| `.default(...)`, `.minLength()/.maxLength()` | **no** | subclass-only → **absent at compile time** (`PgVectorFieldBuilder` doesn't declare them). |
| validators | **no (Phase 1)** | dimension is the only constraint and is built-in (§4). |

> This is weaker than "every invalid modifier is a compile error" — `.unique()`
> compiles and fails at finalize. Making it a compile error would require extracting a
> minimal modifier base class shared by all builders, a refactor out of scope for
> Phase 1. The `build()`-time rejection is the same mechanism the codebase already uses
> for incompatible-modifier combinations.

### 4. Dimension validation (resolves "dimension validation needs a rule")

Three layers, each with a distinct job:

1. **Declaration (fail fast).** `postgresVector(name, dimensions)` requires
   `dimensions in 1..16000` (the `vector` type's max; HNSW/IVFFlat indexes are
   further capped at 2000, enforced where the index is declared) in the registration function:
   `require(dimensions in 1..16000) { "postgresVector('$name') dimensions must be 1..16000, got $dimensions" }`.
2. **Write (precise, field-named).** The generated `create`/`update` setter for a
   vector field checks the value's dimension against the column's declared
   `dimensions` *before* handing it to the driver:
   `require(value.dimensions == 1536) { "articles.embedding expects vector(1536), got vector(${value.dimensions})" }`.
   `PostgresDriver` re-checks defensively at bind so a hand-built `EntitySchema`
   can't smuggle a wrong-size vector past the generated layer.
3. **Migration (manual/destructive).** A `vector(1536) → vector(3072)` change is
   classified manual/destructive by the diff engine comparing
   `ColumnStorage.Native.dimensions` (§9) — automatic `ALTER` cannot transform
   existing embeddings.

`PgVector` itself carries **no** fixed dimension (it is a value wrapper usable for any
column); the column's declared `dimensions` is the single source of truth.

### 5. Storage metadata: `ColumnStorage` (resolves "runtime metadata needs a shape")

Today `ColumnMetadata.type` is a bare `FieldType` (`runtime/.../EntitySchema.kt:50`),
with no room for "this column is `vector(1536)`". Phase 1 adds one carrier, defined
**once in the `schema` module** (so both `Field` and `runtime.ColumnMetadata` — which
already imports `entkt.schema.FieldType` — can use it):

```kotlin
// schema/src/main/kotlin/entkt/schema/ColumnStorage.kt  (NEW)
sealed interface ColumnStorage {
    /** Dialect-native column (Phase 1: Postgres pgvector). */
    data class Native(
        val dialect: String,            // "postgres"
        val typeName: String,           // "vector"
        val sqlType: String,            // "vector(1536)" — rendered verbatim into DDL
        val codec: String,              // "postgres.vector" — the driver's bind/decode dispatch key
        val requiredExtension: String?, // "vector" → CREATE EXTENSION IF NOT EXISTS vector
        val dimensions: Int,            // 1536 — used by the write check + diff classification
    ) : ColumnStorage
    // Custom scalar converters would add a separate Converted variant; see custom-scalar-converters.md.
}
```

No `KClass` is stored (the schema module can't reference `PgVector`); codegen derives
the Kotlin property type from `FieldType.PGVECTOR` and the driver derives bind/decode
from `codec`.

**`FieldType` gains one value:** `PGVECTOR` (`FieldType.kt`). It is the discriminator
codegen and the driver switch on; the `ColumnStorage.Native` alongside it carries the
specifics. (We add the explicit `FieldType` rather than a generic `NATIVE` sentinel so
existing exhaustive `when(FieldType)` sites fail to compile until each is handled —
turning "did we cover the driver/migration/codegen path?" into a compiler check.)

**It must be threaded at every hop** (the reviewer's explicit ask — here is each one):

| Hop | File (today) | Phase-1 change |
| --- | --- | --- |
| Builder → `Field` | `FieldBuilder` (`build()` final) / `Field.kt` | add `Field.storage: ColumnStorage? = null`; the `@PublishedApi internal setNativeStorage` is set at registration and folded in by the final `build()` (§2). |
| `Field` → `ColumnDescriptor` | `codegen/.../SchemaMetadata.kt:104-243` | copy `field.storage` into the descriptor. |
| descriptor → emitted `ColumnMetadata` | `SchemaMetadata.kt:421-531` | add `ColumnMetadata.storage: ColumnStorage? = null`; emit the literal. |
| `ColumnMetadata` → migrations | `NormalizedSchema.kt:31` | add `NormalizedColumn.storage`; copy through. |
| driver bind/decode | `PostgresDriver` (§8) | dispatch on `storage`. |
| DDL type | `TypeMapper.sqlTypeFor` (`migrations/.../Interfaces.kt:16`) | **signature change required**: today it is `sqlTypeFor(fieldType, isPrimaryKey, idStrategy)` and `NormalizedSchema.kt:31` calls it with `col.type` only. Add a defaulted `storage: ColumnStorage? = null` param (backward-compatible) and pass `col.storage`; `PostgresTypeMapper` returns `storage.sqlType` for `PGVECTOR`. |
| introspection (read-back) | `PostgresIntrospector` | reconstruct `Native` from `pg_attribute`/`pg_type` + `pg_index`/`pg_opclass`. |

### 6. Native index metadata (resolves "vector indexes need new metadata")

An index travels through **three** data models — the schema-side `Index`
(`schema/.../Index.kt:3`, what the DSL builder produces), the runtime `IndexMetadata`
(`runtime/.../EntitySchema.kt:107`, what codegen emits and the driver/migrations read),
and the migrations `NormalizedIndex` (`migrations/.../NormalizedSchema.kt:111`). **All
three are btree-shaped today** (`name/fields/unique/where`) and **all three** must gain
the same **three nullable** fields so existing btree indexes stay byte-identical:

```kotlin
//   schema Index (Index.kt) · runtime IndexMetadata (EntitySchema.kt) · NormalizedIndex
val using: String? = null,            // access method: "hnsw" / "ivfflat"  (null = btree)
val opclasses: List<String>? = null,  // per-column operator class: ["vector_cosine_ops"]
val with: Map<String, String>? = null // index storage params: IVFFlat {"lists":"100"}, HNSW {"m":"16",...}
```

The `with` map is what carries IVFFlat `lists` (and any HNSW build params) — `using` +
`opclasses` alone cannot.

The DSL parallels the existing `index(...)` builder:

```kotlin
postgresVectorIndex("idx_articles_embedding_hnsw", embedding).hnsw(VectorMetric.Cosine)
//  → Index(fields=["embedding"], name=..., using="hnsw", opclasses=["vector_cosine_ops"])
postgresVectorIndex("idx_articles_embedding_ivf", embedding).ivfflat(VectorMetric.L2, lists = 100)
//  → Index(..., using="ivfflat", opclasses=["vector_l2_ops"], with=mapOf("lists" to "100"))
```

`postgresVectorIndex` is an import-gated extension (`entkt.postgres.vector`), so — like
`postgresVector` — it hits the same private-registration blocker §2 solves, and needs
the **same bridge**: `IndexBuilder`'s ctor is `internal` (`IndexBuilder.kt:3`), `index()`
is `protected` (`EntSchema.kt:240`, uncallable from an extension), and `_indexes` is
`internal` (`:61`). Crucially, `index()` also runs **column-ownership validation**
(`EntSchema.kt:243-255`: every referenced column's `declarationOwner` must be this
schema), which the parallel must replicate. So Phase 1 adds a `@PublishedApi internal
fun EntSchema.registerPostgresVectorIndex(name, field)` (does the ownership check,
constructs the `IndexBuilder` via its internal ctor, `_indexes.add`) and a
`@PublishedApi internal IndexBuilder.setVectorIndex(using, opclasses, with)` folded into
the built `Index` by the final `build()` — mirroring `registerPostgresVector` /
`setNativeStorage` exactly. The public `inline postgresVectorIndex` + the
`inline .hnsw(metric)` / `.ivfflat(metric, lists)` (which call `setVectorIndex`) live in
the schema module under `entkt.postgres.vector`.

`VectorMetric` maps to opclass + the matching distance operator:

| `VectorMetric` | opclass | operator |
| --- | --- | --- |
| `Cosine` | `vector_cosine_ops` | `<=>` |
| `L2` | `vector_l2_ops` | `<->` |
| `InnerProduct` | `vector_ip_ops` | `<#>` (pgvector's **negative** inner product — see §10) |

Threading (the three hops): the `postgresVectorIndex` builder sets the fields on the
schema `Index`; codegen (`SchemaMetadata.kt:501`, `schema.indexes()`) copies them into
the emitted `IndexMetadata`; `NormalizedSchema` copies them into `NormalizedIndex`. The
differ's index identity is `IndexKey(columns, unique, where)` (`SchemaDiffer.kt:141`) —
fold `using`/`opclasses`/`with` into it so a `btree → hnsw`, opclass, or `lists` change
is a detected drop+recreate. Migrations then render `CREATE INDEX <name> ON <table>
USING hnsw (embedding vector_cosine_ops)` / `USING ivfflat (...) WITH (lists = 100)` (§9).

### 7. The `PgVector` value type

Lives in the **postgres** module (`entkt.postgres.vector.PgVector` — see the
as-built note above), so the generated property type is Postgres-namespaced and the
schema module stays clean:

```kotlin
class PgVector private constructor(private val values: FloatArray) {
    val dimensions: Int get() = values.size
    operator fun get(i: Int): Float = values[i]
    fun toFloatArray(): FloatArray = values.copyOf()   // defensive copy out
    companion object {
        fun of(values: FloatArray) = PgVector(values.copyOf())          // defensive copy in
        fun of(values: List<Float>) = PgVector(values.toFloatArray())
    }
    override fun equals(other: Any?) = other is PgVector && values.contentEquals(other.values)
    override fun hashCode() = values.contentHashCode()
}
```

The backing array is **never shared**: `of(...)` copies on the way in and
`toFloatArray()` copies on the way out (with read-only `get(i)`/`dimensions` for the
common case), so a caller mutating their array — before or after construction — can't
silently change a `PgVector`'s identity. It is a **regular class with content
equality**, not `FloatArray` exposed directly and not `@JvmInline value class` (a value
class over `FloatArray` inherits referential
array equality, which is surprising inside generated `data class` entities).

### 8. Generated code & the bind/decode boundary (resolves "driver/domain conversion is inconsistent")

The boundary has **two distinct rules**, and Phase 1 only exercises the native one:

- **Native fields (Phase 1, pgvector):** generated code passes the **domain value**
  (`PgVector`) straight into the driver write map (`"embedding" to embedding`); the
  **driver owns** encode/decode by dispatching on `ColumnMetadata.storage`. The driver
  already has the registered schema and looks the column up by name (as
  `columnTypeOf(schema, col)` does today), so `bind` learns it is a vector without a
  signature change to the public `Driver` API:

  ```kotlin
  // PostgresDriver.bind(...) — augmented
  val storage = schemaFor(table).columns.first { it.name == col }.storage
  if (storage is ColumnStorage.Native && storage.codec == "postgres.vector") {
      require((value as PgVector).dimensions == storage.dimensions) { "...expects vector(${storage.dimensions})..." }
      stmt.setObject(idx, PGobject().apply { type = "vector"; this.value = value.values.joinToString(",", "[", "]") })
      return
  }
  // else: existing when(FieldType) path
  ```

  Decode is the mirror: read the `vector` column's text and build `PgVector`.
- **Converted scalar fields (Phase 2):** generated code **encodes to a primitive**
  (`"email" to email.value`) *before* the driver, and the driver binds an ordinary
  primitive via the existing `FieldType` switch — no driver awareness needed. Decode
  wraps in generated `fromRow` (`Email(row["email"] as String)`), exactly like enum's
  `valueOf` today.

Stated as a contract: **native = driver-owned bind/decode keyed off
`ColumnStorage.Native.codec`; converted = codegen-owned encode/decode to a
`FieldType` primitive.** The two never mix.

Generated entity / mutation for the example:

```kotlin
data class Article(val id: Long, val title: String, val embedding: PgVector?)

client.articles.create { title = "…"; embedding = PgVector.of(model.embed(title)) }.save()
```

### 9. Driver compatibility, extension availability, migrations

**Driver compatibility (at `register`, fail at `EntClient` construction).** Add a
capability `Driver.supportsNativeStorage(codec: String): Boolean = false` (mirrors the
existing `supports*` flags). `PostgresDriver` returns true for `"postgres.vector"`.
Generated repos already call `driver.register(Entity.SCHEMA)` in `init`; `register`
walks columns and, for any `ColumnStorage.Native` whose `codec` the driver doesn't
support, throws `UnsupportedDriverCapabilityException`:

```text
UnsupportedDriverCapabilityException:
articles.embedding uses postgres vector(1536), but SQLiteDriver does not support codec 'postgres.vector'
```

**Extension availability — a first-class, ordered migration op.** `register` does
**not** query the live DB for the extension (keeps construction cheap). The DDL is the
migration's job, and it must be a real `MigrationOp`, not free-floating text:
`MigrationOp` today has no extension variant (`MigrationOp.kt:10`) and `sortOps`
(`SchemaDiffer.kt:251`) ranks only `CreateTable(0)/AddColumn(1)/AddIndex(2)/AddForeignKey(3)`,
so emitting `CREATE EXTENSION` as loose SQL could land *after* a `vector(n)` column.
Phase 1 adds:

- `MigrationOp.CreateExtension(name: String)`;
- sort priority **−1** (before `CreateTable`), so the extension always precedes any
  column that needs it;
- the differ emits one `CreateExtension("vector")` when any column carries
  `requiredExtension = "vector"` (deduped across the schema set).

**The runtime `autoDdl` path needs the same.** `PostgresDriver.register(autoDdl = true)`
does **not** go through the migration differ — it builds DDL directly
(`PostgresDriver.kt:72-81`: `createTableSql` + `createIndexesSql`, executed at
registration). So that path must, for any registered schema with a column carrying
`requiredExtension`, issue `CREATE EXTENSION IF NOT EXISTS <ext>` **before** its
`CREATE TABLE` (and create the HNSW/IVFFlat index via the same `using`/`opclasses`/`with`
rendering as migrations). Without this, `autoDdl` creates the `vector(n)` column before
the extension exists and fails.

**Migration DDL** (`PostgresTypeMapper` returns `storage.sqlType` for `PGVECTOR`; the
renderer renders the new ops):

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE articles (
  id bigserial PRIMARY KEY,
  title text NOT NULL,
  embedding vector(1536)
);

CREATE INDEX idx_articles_embedding_hnsw
  ON articles USING hnsw (embedding vector_cosine_ops);
```

- `vector(1536)` comes verbatim from `ColumnStorage.Native.sqlType` via
  `PostgresTypeMapper.sqlTypeFor(PGVECTOR, …)`.
- the `USING hnsw (… vector_cosine_ops)` clause comes from `IndexMetadata.using` +
  `opclasses` (§6).
- a **dimension change** (`vector(1536)→vector(3072)`) is classified
  **manual/destructive** by the existing diff engine — extend the column-type-change
  comparison to look at `storage.dimensions`, reusing the same machinery that already
  defers risky `ALTER`s to hand-written migrations.

### 10. Query surface (Phase 1: nearest-neighbor ordering only)

The one query shape Phase 1 ships is distance **ordering** (the headline pgvector use
case):

```kotlin
val q = PgVector.of(embeddingModel.embed("kotlin orm"))
client.articles.query()
    .orderBy(Article.embedding.cosineDistance(q).asc())
    .limit(20).all()
```

`cosineDistance` / `l2Distance` / `innerProduct` are generated only on vector fields
and lower to the `<=>` / `<->` / `<#>` operators, gated by
`supportsNativeStorage("postgres.vector")` so a non-Postgres driver fails with a
capability error rather than emitting invalid SQL. **Filtering** (`WHERE distance <
threshold`) and other operator families are Phase 2.

> **Inner-product semantics.** pgvector's `<#>` is **negative** inner product
> (`-(a·b)`), precisely because Postgres index scans are *ascending* — so
> `innerProduct(q).asc()` returns the rows with the *largest raw* `a·b` first
> (most-similar-first), the same `.asc()` = most-similar convention as `cosineDistance`
> / `l2Distance`. The helper is named for the operator family, not the raw arithmetic;
> the generated KDoc states "orders by pgvector's `<#>` (negative inner product); use
> `.asc()` for most-similar-first." (pgvector README: `<#>` returns negative inner
> product and HNSW/IVFFlat scans require ascending order —
> <https://github.com/pgvector/pgvector>.)

**This requires a richer order model.** `OrderField` today is a bare
`(field: String, direction)` (`schema/.../query/OrderField.kt:9`), and Postgres renders
it as `"alias"."field" ASC|DESC` (`PostgresDriver.kt:295`) — there is nowhere to put a
distance expression or its bound parameter. Phase 1 generalizes the order-by element to
a sealed type (preserving the existing column case byte-identically):

```kotlin
// Closed operator set — NEVER caller-supplied SQL text (cf. Predicate.Op).
enum class VectorDistanceOperator(val sql: String) { L2("<->"), Cosine("<=>"), NegInnerProduct("<#>") }

sealed interface OrderExpression<E : Any> {
    val direction: OrderDirection
    data class Column<E : Any>(val field: String, override val direction: OrderDirection) : OrderExpression<E>
    // native distance ordering: `<col> <op> <bound vector>` (Phase 1, pgvector)
    data class NativeDistance<E : Any> internal constructor(
        val field: String,
        val operator: VectorDistanceOperator,  // closed enum, not raw text — the driver reads operator.sql
        val operand: PgVector,                  // bound as a parameter, never inlined
        override val direction: OrderDirection,
    ) : OrderExpression<E>
}
```

`operator` is a **closed enum**, not a `String` — the driver reads `operator.sql`, so no
caller-supplied text ever reaches the SQL (the constructor is `internal`; only the
generated `cosineDistance`/`l2Distance`/`innerProduct` helpers build it). The driver's
ORDER BY renderer switches on the variant: `Column` keeps today's `alias.field DIR`;
`NativeDistance` renders `alias.field <op.sql> ? DIR` and **binds the operand as a
parameter** (so embeddings never land in the SQL string). A driver lacking
`supportsNativeStorage("postgres.vector")` rejects a `NativeDistance` element at lowering
time with a capability error. (`OrderField` either becomes a type alias for
`OrderExpression.Column` or is replaced; the migration is mechanical because every
existing `orderBy` produces the `Column` case.)

---

## Related Future Work

Other field families may reuse pieces of this foundation, but they are intentionally
separate proposals so the APIs stay explicit instead of overloading one generic
"custom type" concept:

- [Custom Scalar Converters](../../possible-features/schema/custom-scalar-converters.md) for application-domain
  wrappers stored as ordinary scalar columns.
- [Typed JSON Fields](../../possible-features/schema/typed-json-fields.md) for structured JSON/JSONB values.
- Native database enums, arrays, and vector filtering need separate RFCs before
  implementation.

---

## Test Requirements (Phase 1)

Schema / codegen:

- `postgresVector("embedding", 1536)` generates a `PgVector` (nullable + non-null)
  entity/create/update property; `postgresVector(...).unique()` **compiles but throws at
  `build()`/finalize** with the field-named message (§3), while a subclass-only modifier
  like `postgresVector(...).maxLength(…)` **does not compile** (absent from the builder).
- `postgresVector(name, 0)` and `postgresVector(name, 16001)` fail at declaration; a real size like `3072` (OpenAI text-embedding-3-large) is accepted.
- `Field.storage` / `ColumnMetadata.storage` carry `ColumnStorage.Native("postgres",
  "vector", "vector(1536)", "postgres.vector", "vector", 1536)`.
- `postgresVectorIndex(...).hnsw(Cosine)` threads `using="hnsw"`,
  `opclasses=["vector_cosine_ops"]` through schema `Index` → `IndexMetadata` →
  `NormalizedIndex`; `.ivfflat(L2, lists = 100)` additionally carries
  `with={"lists":"100"}`; a btree `index(...)` is unchanged (`using == null`,
  byte-identical).

Driver (Postgres integration):

- create/update binds `PgVector`; query hydrates `PgVector`; content equality holds.
- a nullable vector round-trips null and non-null.
- a wrong-dimension `PgVector` is rejected at the generated setter (field-named error)
  and defensively at bind.
- a vector schema on a non-Postgres driver throws `UnsupportedDriverCapabilityException`
  during `register` (at `EntClient` construction).
- nearest-neighbor `orderBy(embedding.cosineDistance(q).asc())` lowers to `<=>` and
  returns rows in distance order; the same helper on a non-Postgres driver fails with a
  capability error.

Migrations:

- rendering emits `CREATE EXTENSION IF NOT EXISTS vector` (once) and `vector(n)`
  column types.
- HNSW and IVFFlat indexes render `USING hnsw (col vector_cosine_ops)` /
  `USING ivfflat (...) WITH (lists = N)` with the expected opclasses.
- a `vector(1536) → vector(3072)` change is classified manual/destructive.
- introspection reads a live `vector(1536)` + HNSW index back into the same
  `ColumnStorage.Native` / `IndexMetadata`.

---

## Appendix: the `enum` blueprint

Phase 1 mirrors the existing typed-enum pipeline hop-for-hop; this is why the design
is low-risk:

| Layer | `enum` (today) | `postgresVector` (Phase 1) |
| --- | --- | --- |
| DSL entry | `protected inline enum<E>(name)` → `@PublishedApi internal enum(name, KClass)` (`EntSchema.kt:170-181`) | `inline EntSchema.postgresVector(name, dims)` → `@PublishedApi internal registerPostgresVector(...)` (§2) |
| builder | `EnumFieldBuilder` (internal ctor, `FieldType.ENUM`) | `PgVectorFieldBuilder` (internal ctor, `FieldType.PGVECTOR`) |
| field metadata | `Field.enumClass: KClass?` | `Field.storage: ColumnStorage.Native` (§5) |
| codegen type | property = the Kotlin enum; `fromRow` = `valueOf` | property = `PgVector`; decode in driver (§8) |
| driver | binds/decodes enum as `text` | binds/decodes vector via `PGobject` keyed on `codec` (§8) |
| migration | renders `text` (or PG enum) | renders `vector(n)` + extension + index (§9) |

The one structural addition Phase 1 makes over `enum` is the generic `ColumnStorage`
carrier (enum hardcodes `enumClass`); that carrier is what lets Phases 2+ land without
touching the foundation.
