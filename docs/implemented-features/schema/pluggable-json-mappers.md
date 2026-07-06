# RFC: Pluggable JSON Mappers (Jackson Option for Typed JSON Fields)

## Status

**Implemented** (2026-07-05). Originally recorded as a design note when the
deferral condition in [Typed JSON Fields](typed-json-fields.md) ("entkt will
not introduce a generic JSON mapper abstraction until there is a concrete
need for more than one mapper") was met: a consuming Jackson project flagged
that `json(...)` columns forced adopting kotlinx.serialization alongside
Jackson. The sections below are the design contract, kept as a record.
User-facing docs:
[Schema -> Typed JSON Fields](../../02-schema.md#typed-json-fields-postgres-jsonb).

### As-built notes

The implementation followed this contract closely:

- SPI, ids, metadata, and the kotlinx codec live in `entkt.runtime.driver`
  (`JsonColumnCodec`, `JsonMapperIds`, `JsonColumnMetadata`,
  `KotlinxJsonCodec`); `JacksonJsonCodec` ships as its own module,
  `io.entkt:jackson` (zero Jackson dependency in core, as specced).
- The metadata landed exactly as the flat-with-id shape below; `register()`
  cross-checks `column.json.mapper == codec.id` in `PostgresDriver` and then
  calls `codec.validate(table, column)`.
- The option threads `EntGenerator(packageName, jsonMapper)` →
  `GenerateMain <packageName> <outputDir> [jsonMapper]` → the Gradle plugin's
  `entkt { jsonMapper }` property (a plain string on the plugin side — it
  deliberately stays off entkt's classloader).
- Both compile-time contracts are pinned by tests: the same
  non-`@Serializable` schema that fails to compile under the kotlinx mapper
  compiles under the Jackson mapper (`JsonCompileFailTest`), and a
  generate/configure mismatch fails at `register()` in both directions
  (`JacksonPostgresIntegrationTest`).
- One implementation wrinkle: Jackson's `readValue(String, JavaType)` has a
  return-position-only generic that Kotlin infers as `Void` in some contexts;
  the codec passes the type argument explicitly.

## Problem

There is **no way to configure which JSON library typed JSON columns use**.
kotlinx.serialization is hard-wired at three layers:

1. **Codegen** bakes kotlinx serializer expressions into the generated
   `SCHEMA` literal — `PetMetadata.serializer()`,
   `ListSerializer(Rect.serializer())`. This is also what implements the
   compile-time-safety contract (a type without a serializer fails at
   consumer compile time).
2. **Runtime metadata** — `JsonColumnMetadata.serializer` is a
   `kotlinx.serialization.KSerializer<*>`.
3. **Driver** — `PostgresDriver` takes a `kotlinx.serialization.json.Json`
   and calls `encodeToString` / `decodeFromString`.

The only configuration today is which kotlinx `Json` *instance* the driver
uses (`PostgresDriver(json = Json { ignoreUnknownKeys = true })`). Jackson
annotations on element classes are ignored; a Jackson shop must apply the
kotlinx compiler plugin and annotate json-column classes `@Serializable`
(the two frameworks coexist without conflict, but it is a second framework).

## Why not an existing abstraction

There is no established JSON abstraction that both libraries implement.
Jakarta JSON-B (`jakarta.json.bind.Jsonb`) is the closest standard, but
kotlinx does not implement it, it is Java-reflective (weak Kotlin
nullability/default-parameter semantics), and adopting it adds a third
framework that satisfies neither camp. Wrapping Jackson as a kotlinx
`SerialFormat` inverts the problem: every call site still needs
`@Serializable` and the compiler plugin, which is exactly what a Jackson
shop is trying to avoid. entkt would own its own minimal SPI.

## Design sketch — two coordinated layers

The runtime surface is genuinely narrow (the driver only ever converts a
value to/from `jsonb` text for a known column), so the SPI is small:

```kotlin
interface JsonColumnCodec {
    /** Stable codec id, matched against JsonColumnMetadata.mapper at register(). */
    val id: String
    /** Reject unsupported column types at register() (fail fast, not at first read). */
    fun validate(table: String, column: ColumnMetadata)
    fun encode(table: String, column: ColumnMetadata, value: Any): String
    fun decode(table: String, column: ColumnMetadata, text: String): Any
}

PostgresDriver(dataSource, jsonCodec = KotlinxJsonCodec(Json.Default))   // default
PostgresDriver(dataSource, jsonCodec = JacksonJsonCodec(objectMapper))   // opt-in, separate module
```

Every method takes the table name because `ColumnMetadata` doesn't carry it —
codec errors must name `table.column`, matching the existing driver
encode/decode error contract (today's `bindJson`/`decodeColumn` thread
`table` for the same reason).

Both implementations are thin because `Field.jsonType` already carries the
full `KType` (added with generic-type support): the kotlinx codec uses the
codegen-emitted `KSerializer`; the Jackson codec derives a `JavaType` via
`kType.javaType` → `TypeFactory.constructType(...)`, generics like
`List<Rect>` included.

**Metadata shape.** `JsonColumnMetadata` is redefined concretely — the
mapper id is the discriminant `register()` checks against, and the kotlinx
serializer becomes optional payload whose presence is tied to that id:

```kotlin
data class JsonColumnMetadata(
    /** Erased classifier backing the write-time isInstance check (unchanged). */
    val klass: KClass<*>,
    /** Mapper-neutral full type — codegen emits `typeOf<List<Rect>>()`. */
    val kType: KType,
    /** Rendered type for diagnostics (unchanged). */
    val typeName: String,
    /** Stable codec id this column's generated code targets: "kotlinx", "jackson". */
    val mapper: String,
    /** Present iff mapper == "kotlinx" — the statically-emitted serializer. */
    val kotlinxSerializer: KSerializer<*>? = null,
) {
    init {
        require((mapper == "kotlinx") == (kotlinxSerializer != null)) {
            "kotlinxSerializer must be present exactly when mapper == kotlinx"
        }
    }
}
```

A sealed hierarchy (`JsonColumnMetadata.Kotlinx` / `.Reflective`) would make
the invariant structural, but seals the set of mappers into core runtime — a
flat id keeps the SPI open to third-party codecs (Moshi) without touching
core. The `kType` carrier is emitted as a `typeOf<T>()` expression, which is
compile-safe in generated code. Renaming today's non-null `serializer` field
is a breaking change to log if this ships.

**The codec cannot be runtime-only — the mapper choice must reach codegen.**
Generated `SCHEMA` literals currently bake in kotlinx serializer expressions
(`ListSerializer(Rect.serializer())`); for a Jackson project those are
unresolved references and the generated code does not compile at all. The
option's single source of truth is an `EntGenerator` constructor parameter
(`jsonMapper: String = "kotlinx"`), threaded through both entry points:
`GenerateMain` grows an optional third argument
(`GenerateMain <packageName> <outputDir> [jsonMapper]`) so direct JavaExec
callers set it, and the Gradle plugin DSL (`entkt { jsonMapper = "jackson" }`)
is sugar that passes the same value through. It selects what codegen emits
into each column's `mapper` field and whether serializer expressions are
emitted at all. The migration path (`buildEntitySchemas`) carries no JSON
metadata and is unaffected.

**Startup cross-check.** For every `FieldType.JSON` column, `register()`
requires `column.json.mapper == codec.id` and fails otherwise with an error
naming the table.column, the metadata's mapper (what the code was generated
for), and the configured codec — so regenerating with one mapper while the
driver is configured with another fails at startup, not at first read.

**Mapper ids and typos.** Built-in ids are constants shared by the codegen
setting, the metadata init check, and the codecs —
`JsonMapperIds.KOTLINX` / `JsonMapperIds.JACKSON` — so built-in users never
type raw strings (the Gradle DSL and `EntGenerator` accept the constants;
raw strings remain the escape hatch for third-party codecs). Codegen cannot
reject an unknown id (openness to third-party codecs is the point), so a
typo'd id flows into the generated metadata verbatim — and is then caught by
the register() cross-check above, since no configured codec advertises the
typo'd id. Document that behavior explicitly: an id typo is a guaranteed
startup failure naming both ids, never a silent fallback to kotlinx.

Module layout: SPI + kotlinx codec in `runtime` (kotlinx stays the default,
zero-config path); `entkt-jackson` as its own module so core keeps zero
Jackson dependency.

## The hard tradeoff

The **compile-time-safety contract dies for Jackson columns.** kotlinx's
guarantee comes from codegen emitting `X.serializer()` references; Jackson is
reflective, so the equivalent failure moves to `register()` time at best
(`codec.validate` preflights `TypeFactory`/`canSerialize` checks). The same
`json<T>(...)` declaration would have a different failure mode depending on a
build setting — a least-surprise cost that must be documented prominently if
this ships. Round-trip semantics (absent-vs-null, Kotlin default parameters)
are also codec-owned and differ between the two; entkt just stores text.

## Alternatives considered

- **Document coexistence only (status quo).** kotlinx annotations don't
  interfere with Jackson databind; the cost is a compiler plugin + one
  annotation per json class. Cheapest, keeps the compile-time contract.
- **Raw-text escape hatch** — a `jsonText("col")` field exposing `String`
  over `jsonb`, letting applications run any mapper themselves. Preserves
  every contract (no serializer in metadata at all), costs ergonomics.
  Could ship independently of, or instead of, a codec SPI.

## Non-goals

- Per-field mapper mixing within one entity.
- Abstracting the query layer over mappers (JSON predicates are deferred
  anyway).
