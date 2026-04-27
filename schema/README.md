# :schema

Declarative schema DSL — `EntSchema`, field/edge/index builders, `FieldType`.

## Field types

`STRING`, `TEXT`, `BOOL`, `INT`, `LONG`, `FLOAT`, `DOUBLE`,
`TIME` (`Instant`), `UUID`, `BYTES`, `ENUM` (Kotlin enum classes via
`enum<E>()`).

**Enums:** `enum<MyStatus>("status")` binds the field to a Kotlin enum
class — entity properties, builders, query predicates, and defaults are all
fully typed. Defaults must be constants from the correct enum class.
Stored as strings in the database.

## Field modifiers

`.optional()`, `.unique()`, `.immutable()`,
`.sensitive()`, `.comment(...)`, `.default(value)` (type-safe per field type).
Time fields also support `.defaultNow()` and `.updateDefaultNow()` (emit `Instant.now()`).

**Type-specific validators** (enforced as inline checks in generated `save()` methods):
- Strings: `.minLen()`, `.maxLen()`, `.notEmpty()`, `.match(regex)`
- Numbers: `.min()`, `.max()`, `.positive()`, `.negative()`, `.nonNegative()`

## Id strategies

`EntId.int()` / `.long()` / `.uuid()` / `.string()`:
`AUTO_INT`, `AUTO_LONG`, `CLIENT_UUID`, `EXPLICIT`.

## Edges

`belongsTo<Target>(name)` (FK-owning side), `hasMany<Target>(name)` (one-to-many),
`hasOne<Target>(name)` (one-to-one), `manyToMany<Target>(name)` (via junction).
Modifiers: `.inverse(Target::edge)`, `.required()`, `.unique()`,
`.field(handle)`, `.through<Junction>(Junction::src, Junction::tgt)`,
`.onDelete(OnDelete.CASCADE | SET_NULL | RESTRICT)`.

## Reusable mixins

Reusable local field/index bundles can be modeled as `EntMixin`s and included
into a schema with `include(...)`. Relationship edges stay on the host schema.

```kotlin
class Timestamps(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt = time("created_at").immutable()
    val updatedAt = time("updated_at")
}

class Post : EntSchema("posts") {
    override fun id() = EntId.long()

    val timestamps = include(::Timestamps)
    val title = string("title")
}
```

Declarations created inside a mixin register on the including schema in the
same order that `include(...)` appears.

## Indexes

`index("name", field1, field2)` + `.unique()` + `.where(predicate)` (partial indexes).
