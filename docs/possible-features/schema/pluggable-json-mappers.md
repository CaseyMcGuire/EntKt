# RFC: Pluggable JSON Mappers (Jackson Option for Typed JSON Fields)

## Status

**Design note — not committed.** Recorded because the deferral condition in
[Typed JSON Fields](../../implemented-features/schema/typed-json-fields.md)
("entkt will not introduce a generic JSON mapper abstraction until there is a
concrete need for more than one mapper") has been met: a consuming Jackson
project flagged that `json(...)` columns force adopting kotlinx.serialization
alongside Jackson.

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
    /** Reject unsupported column types at register() (fail fast, not at first read). */
    fun validate(column: ColumnMetadata)
    fun encode(column: ColumnMetadata, value: Any): String
    fun decode(column: ColumnMetadata, text: String): Any
}

PostgresDriver(dataSource, jsonCodec = KotlinxJsonCodec(Json.Default))   // default
PostgresDriver(dataSource, jsonCodec = JacksonJsonCodec(objectMapper))   // opt-in, separate module
```

Both implementations are thin because `Field.jsonType` already carries the
full `KType` (added with generic-type support): the kotlinx codec uses the
codegen-emitted `KSerializer`; the Jackson codec derives a `JavaType` via
`kType.javaType` → `TypeFactory.constructType(...)`, generics like
`List<Rect>` included. `JsonColumnMetadata` grows the `KType` as the
mapper-neutral carrier (`typeName` stays diagnostic-only); the kotlinx
`serializer` field becomes codec-specific payload.

**The codec cannot be runtime-only — the mapper choice must reach codegen.**
Generated `SCHEMA` literals currently bake in kotlinx serializer expressions
(`ListSerializer(Rect.serializer())`); for a Jackson project those are
unresolved references and the generated code does not compile at all. So the
real shape is a codegen-level setting (`entkt { jsonMapper = KOTLINX |
JACKSON }` on the Gradle plugin) that decides whether serializer expressions
are emitted, plus the matching runtime codec on the driver — with
`register()` cross-checking that generated metadata and configured codec
agree, so a mismatch fails loudly at startup instead of at first read.

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
