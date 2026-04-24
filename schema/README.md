# :schema

Declarative schema DSL — `EntSchema`, field/edge/index/mixin builders, `FieldType`.

## Field types

`STRING`, `TEXT`, `BOOL`, `INT`, `LONG`, `FLOAT`, `DOUBLE`,
`TIME` (`Instant`), `UUID`, `BYTES`, `ENUM` (untyped string values or
typed Kotlin enum classes via `enum<E>()`).

**Typed enums:** `enum<MyStatus>("status")` binds the field to a Kotlin enum
class — entity properties, builders, query predicates, and defaults are all
fully typed. Defaults must be constants from the correct enum class.
Stored as strings in the database.

## Field modifiers

`.nullable()`, `.unique()`, `.immutable()`,
`.sensitive()`, `.comment(...)`, `.storageKey(...)`, `.default(...)`.
Time fields also support `.updateDefaultNow()` (emit `Instant.now()` on every update).

**Type-specific validators** (enforced as inline checks in generated `save()` methods):
- Strings: `.minLen()`, `.maxLen()`, `.notEmpty()`, `.match(regex)`
- Numbers: `.min()`, `.max()`, `.positive()`, `.negative()`, `.nonNegative()`

## Id strategies

`EntId.int()` / `.long()` / `.uuid()` / `.string()`:
`AUTO_INT`, `AUTO_LONG`, `CLIENT_UUID`, `EXPLICIT`.

## Edges

`to(name, target)` (one-to-many), `from(name, target)` (inverse,
synthesizes FK on source). Modifiers: `.unique()`, `.required()`, `.ref(...)`,
`.field(...)`, `.through(junctionTable, sourceCol, targetCol)` (many-to-many
via junction table), `.onDelete(OnDelete.CASCADE | SET_NULL | RESTRICT)`.

## Mixins

Any `EntMixin` contributing `fields()` and `indexes()`.

## Indexes

Field list + `.unique()` + `.storageKey()` + `.where(predicate)` (partial indexes).
