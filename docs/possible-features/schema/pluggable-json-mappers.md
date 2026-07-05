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

## Design sketch

A **driver-level codec option**, not a per-field one: the schema DSL stays
mapper-agnostic (it already is — `Field.jsonType` carries a plain `KType`,
which is exactly what Jackson's `TypeFactory` needs to build a `JavaType`,
including generics like `List<Rect>`).

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

- `JsonColumnMetadata` grows a mapper-neutral type carrier. `typeName`
  (added with generic-type support) is diagnostic-only; Jackson needs the
  real `KType` (or a `java.lang.reflect.Type` derived from it) in the
  metadata so `TypeFactory.constructType(...)` can target `List<Rect>`.
  The kotlinx `serializer` field becomes codec-specific payload.
- The Jackson codec lives in its own module (`entkt-jackson`?) so the core
  postgres module keeps zero Jackson dependency.

## The hard tradeoff

The **compile-time-safety contract dies for Jackson columns.** kotlinx's
guarantee comes from codegen emitting `X.serializer()` references; Jackson is
reflective, so the equivalent failure moves to `register()` time at best
(`codec.validate` can preflight `TypeFactory`/`canSerialize` checks). Codegen
must know which columns skip serializer emission — either a global codegen
flag or a per-field `json<T>(name, codec = ...)` marker, both of which leak
the mapper choice into the schema after all. This tension (least surprise:
same declaration, different failure mode by configuration) is the main reason
this stays a design note rather than a commitment.

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
