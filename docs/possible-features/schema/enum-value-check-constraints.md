# RFC: Enum Value CHECK Constraints

## Status

Possible future feature. This is not implemented.

Companion to the rename caveat documented in
[`docs/02-schema.md`](../../02-schema.md) ("renaming an enum constant is a
data migration, not a refactor"). That caveat *describes* the hazard; this
RFC *enforces* it.

## Summary

Emit a `CHECK (col IN (...))` constraint for every text-backed enum column,
listing the enum's current constant names. The constraint turns today's
silent failure mode — renaming or removing an enum constant leaves stale
rows that crash on read — into a loud, deterministic failure at migration
time, and gives the migration differ the enum-value awareness it currently
lacks.

## Motivation

Enum fields persist as the constant's `.name` in a plain `text` column
(`PostgresTypeMapper.sqlTypeFor` maps `FieldType.ENUM` → `"text"`). There is
no native database enum and no `CHECK` constraint, so the database accepts
any string. The read path is `EnumType.valueOf(row["col"] as String)`
(`EntityGenerator`), which throws `IllegalArgumentException` on an unknown
name.

The dangerous consequence shows up when a constant is **renamed** (e.g.
`MEDIUM` → `NORMAL`):

- Existing rows keep `'MEDIUM'`. They now fail `valueOf` on read.
- If the constant was a `.default(...)`, the differ emits only a
  metadata-only `ALTER COLUMN … SET DEFAULT 'NORMAL'`
  (`SchemaDiffer.diffColumns`), which does **not** rewrite rows.
- Nothing in the database or the diff tool flags the stale values. The
  migration applies cleanly; the breakage surfaces later, at read time, in
  application code — far from the change that caused it.

A `CHECK` constraint closes this gap. With
`CHECK (status IN ('LOW','MEDIUM','HIGH'))` in place, renaming `MEDIUM` to
`NORMAL` produces a migration that recreates the constraint as
`CHECK (status IN ('LOW','NORMAL','HIGH'))`. Applying that `ADD CONSTRAINT`
**validates existing rows and fails** if any row still holds `'MEDIUM'` —
forcing a deliberate backfill at migration time instead of a silent
production crash later.

## Non-Goals

- **Not** native PostgreSQL `ENUM` types or `ALTER TYPE … RENAME VALUE`.
  That is a separate, larger direction that would build on the native storage
  foundation in [Native Database Column Types](native-database-column-types.md).
  This RFC keeps the existing `text` storage and adds enforcement on top of it.
- **Not** automatic rename *detection*. entkt cannot know that `MEDIUM` was
  renamed to `NORMAL` rather than `MEDIUM` removed and `NORMAL` added —
  codegen has no memory of prior constant names. Detecting a value-set
  *change* is sufficient: the regenerated `CHECK` enforces the new set, and
  the data backfill is the author's responsibility.
- **Not** a change to the application read/write mapping (still `.name` /
  `valueOf`).
- **Not** multi-value or expression CHECK constraints in general; only the
  enum membership constraint.

## Proposed Schema API

No new required surface. The constraint is derived automatically from the
enum class already supplied to `enum<E>("col")`, so existing schemas gain
enforcement for free.

One opt-out, for teams that prefer application-only validation or that have
legacy columns they are not ready to constrain:

```kotlin
val priority = enum<Priority>("priority").default(Priority.LOW)   // CHECK emitted
val legacyKind = enum<Kind>("kind").noCheck()                     // no CHECK emitted
```

`noCheck()` is purely a migration concern (it suppresses constraint
generation); the read/write mapping is unchanged.

## Design

The enum value set must reach the migration layer, which today only sees
`sqlType = "text"`. Thread it through the existing column-metadata pipeline:

1. **Source.** `Field.enumClass` already holds the Kotlin enum class; its
   ordered constant names (`enumClass.java.enumConstants.map { it.name }`)
   are the value set.
2. **Codegen carrier.** Add `checkValues: List<String>?` to
   `ColumnDescriptor` (`codegen/SchemaMetadata.kt`), populated in
   `columnMetadataFor` for `FieldType.ENUM` fields that did not opt out.
3. **Runtime carrier.** Add `checkValues: List<String>? = null` to
   `ColumnMetadata` (`runtime/EntitySchema.kt`), populated on the
   migration-build path (`buildEntitySchema`) exactly as `default` is today.
   (As with `default`, the generated runtime `SCHEMA` literal may omit it —
   it is consumed only by the migration pipeline.)
4. **Normalized carrier.** Add `check: String? = null` to `NormalizedColumn`
   (`migrations/NormalizedSchema.kt`) holding the canonical membership
   expression, produced from `checkValues` by a `TypeMapper` method
   (`formatEnumCheck(column, values)`), e.g. `"col" IN ('LOW','MEDIUM','HIGH')`.
   Introspected columns hold the database-reported constraint definition.

### DDL rendering (`PostgresSqlRenderer`)

- `CREATE TABLE`: append a named, table-level constraint per enum column:
  `CONSTRAINT "<name>" CHECK ("col" IN ('LOW','MEDIUM','HIGH'))`.
- `ADD COLUMN`: emit the column, then a separate
  `ALTER TABLE … ADD CONSTRAINT "<name>" CHECK (…)`.
- Constraint name: derived as `ck_<table>_<col>_enum`, run through
  `TypeMapper.normalizeIdentifier` (63-byte truncation with hash suffix),
  mirroring index/FK naming.
- **NULL handling needs no special case:** for a nullable enum column,
  `NULL IN (…)` evaluates to `NULL`, and a `CHECK` only rejects rows that
  evaluate to `FALSE`, so `NULL` passes. Nullable enums work unchanged.

### Migration ops (`MigrationOp`)

Add two ops, mirroring the index/FK pattern:

- `AddCheckConstraint(table, name, expression)`
- `DropCheckConstraint(table, name)`

A value-set *change* lowers to `DropCheckConstraint` + `AddCheckConstraint`
(Postgres has no in-place `ALTER … CHECK`).

### Diffing and safety (`SchemaDiffer`)

Compare `normalizeCheck(desired.check)` against
`normalizeCheck(current.check)` per column (see Normalization below) and
classify by how the value set moved:

| Change | Effect on existing rows | Classification |
|---|---|---|
| **Add** the constraint to a column that lacked one (first rollout, or new column) | Validates current rows; passes iff data is already valid | **auto** (a column the app wrote can only hold valid names) |
| **Widen** — added constant(s), no removals | New set is a superset; recreate always succeeds | **auto** |
| **Narrow** — any removed/renamed constant | `ADD CONSTRAINT` fails if any row holds a removed value | **manual** |
| **Drop** the constraint (opt-out / field removed) | None | **auto** |

The narrowing case is the whole point: routing it to **manual** surfaces the
rename as an explicit, guided step rather than a clean-applying migration
that hides a read-time landmine. The manual checklist entry should name the
removed value(s) and suggest the backfill, e.g.:

```
-- [ ] CheckConstraint narrows status to ('LOW','NORMAL','HIGH') — removes 'MEDIUM'.
--     Backfill first, e.g.:  UPDATE status SET col = 'NORMAL' WHERE col = 'MEDIUM';
```

This reuses the existing `ManualMode` machinery (`FAIL` /
`ACKNOWLEDGE_AND_ADVANCE`) — no new mode.

### Introspection and normalization (`PostgresIntrospector`)

Read CHECK constraints from `pg_constraint` (`contype = 'c'`) with
`pg_get_constraintdef(oid)` and associate each to its column. The hard part
is **canonicalization**, exactly as with `normalizeWhere` / `normalizeDefault`:
Postgres does not store `col IN ('A','B')` verbatim — it deparses it as

```
CHECK (((col)::text = ANY (ARRAY['A'::text, 'B'::text, 'C'::text])))
```

So a new `normalizeCheck(expr)` must reduce both the desired
(`col IN ('A','B','C')`) and the deparsed `= ANY (ARRAY[...])` forms to a
single comparable representation — e.g. the **sorted set of literal values**
— stripping casts, parens, and `ARRAY[...]`/`IN (...)` syntax. Comparing
sorted value-sets (rather than literal strings) also makes the diff
order-insensitive, so reordering enum constants in Kotlin is a no-op
migration.

## Generated Behavior

For:

```kotlin
enum class Priority { LOW, MEDIUM, HIGH }
class Ticket : EntSchema("tickets") {
    override fun id() = EntId.int()
    val priority = enum<Priority>("priority").default(Priority.LOW)
}
```

`CREATE TABLE`:

```sql
CREATE TABLE "tickets" (
  "id" serial PRIMARY KEY,
  "priority" text DEFAULT 'LOW' NOT NULL
    CONSTRAINT "ck_tickets_priority_enum" CHECK ("priority" IN ('LOW', 'MEDIUM', 'HIGH'))
)
```

Renaming `MEDIUM` → `NORMAL` then generates a manual migration:

```sql
-- !! MANUAL STEPS REQUIRED !!
-- [ ] CheckConstraint narrows priority to ('LOW','NORMAL','HIGH') — removes 'MEDIUM'.
--     Backfill first, e.g.: UPDATE tickets SET priority = 'NORMAL' WHERE priority = 'MEDIUM';
-- ...guard...
ALTER TABLE "tickets" DROP CONSTRAINT "ck_tickets_priority_enum";
ALTER TABLE "tickets" ADD CONSTRAINT "ck_tickets_priority_enum" CHECK ("priority" IN ('LOW', 'NORMAL', 'HIGH'));
-- SET DEFAULT 'NORMAL' (auto)
```

Adding a constant (`URGENT`) is a clean auto migration (drop + re-add with
the wider set).

## Migration of Existing Deployments

Databases created before this feature have enum columns with no CHECK. On
first run after adoption, the differ emits an **auto** `AddCheckConstraint`
with the full current value set. That `ADD CONSTRAINT` validates existing
data:

- Clean data (the normal case, since the app only ever wrote valid names) →
  succeeds, enforcement now in place.
- Dirty data (e.g. values written outside entkt) → fails loudly, which is
  the correct signal.

The shadow-DB verification step has no rows, so it cannot pre-validate this;
the `AddCheckConstraint` is real DDL the differ generates against the live
database. This is acceptable and consistent with how new constraints behave
generally.

## API Shape

```kotlin
// runtime
data class ColumnMetadata(
    // ...
    val checkValues: List<String>? = null,   // enum member names, or null
)

// migrations
data class NormalizedColumn(
    // ...
    val check: String? = null,               // canonical membership expression
)

interface TypeMapper {
    // ...
    fun formatEnumCheck(column: String, values: List<String>): String
}

sealed interface MigrationOp {
    data class AddCheckConstraint(val table: String, val name: String, val expression: String) : MigrationOp
    data class DropCheckConstraint(val table: String, val name: String) : MigrationOp
}
```

## Alternatives Considered

- **Documentation only** (the shipped caveat). Cheap, but leaves the hazard
  unenforced — relies on every author reading the doc.
- **Native PostgreSQL enum types.** Stronger typing and `ALTER TYPE …
  RENAME VALUE` for true renames, but a much larger change (new storage
  model, type lifecycle, driver work) that should get its own RFC on top of
  [Native Database Column Types](native-database-column-types.md).
- **Application-level validation only.** Already effectively present
  (`valueOf` throws), but it fails at read time on arbitrary rows rather
  than at migration time on the change itself — exactly the problem.

## Test Requirements

- DDL: `CREATE TABLE` and `ADD COLUMN` emit the named CHECK with the correct
  value list; nullable enum columns emit the same CHECK (NULL passes).
- Differ: add-constraint and widening classify **auto**; any narrowing
  (removed/renamed value) classifies **manual** with a checklist entry that
  names the removed value(s); constant reordering produces **no** op.
- Normalization: `normalizeCheck` reconciles `col IN ('A','B')` with the
  deparsed `(col)::text = ANY (ARRAY['A'::text,'B'::text])` and is
  order-insensitive.
- Real-Postgres round-trip (flyway): create an enum column, introspect, and
  confirm no drift; widening round-trips as auto; a narrowing migration
  applied against a table holding the removed value **fails loudly**.
- `noCheck()` suppresses constraint generation and diffing for that column.
- Backfill story: a narrowing migration plus the suggested `UPDATE` applies
  cleanly end-to-end.
