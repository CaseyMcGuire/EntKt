# RFC: Schema Validation And Explain

## Status

All three phases are implemented.

### What shipped

- `SchemaInspector` object with `validate()`, `explain()`,
  `renderText()`, and `renderJson()` in the codegen module
- `ExplainedSchema` data model (`ExplainedSchemaGraph`, `ExplainedField`,
  `ExplainedForeignKey`, `ExplainedEdge`, `ExplainedIndex`, etc.)
- `collectSchemas()` — classpath scanner that returns unfinalized inputs,
  separated from `scanForSchemas()` so the inspector controls
  finalization inside its own error handling
- `InspectMain` CLI entry point in the postgres module dispatching
  `validate` / `explain [--format=text|json|sql]` modes
- `validateEntSchemas` and `explainEntSchemas` Gradle tasks registered
  by the plugin (and manually in example-spring), with `-Pformat=`
  property forwarding for `explainEntSchemas`
- Text rendering with markdown-style table formatting (`|` and `---`
  separators)
- JSON rendering — deterministic, no serialization library dependency
- SQL rendering — full DDL via `NormalizedSchema` + `PostgresSqlRenderer`
  pipeline (requires postgres module, which is why `InspectMain` lives
  there)
- Synthesized unique-column indexes (`idx_<table>_<col>_unique`)
  included in the Indexes section alongside explicit indexes
- Edge-driven uniqueness reflected on field columns (e.g.
  `belongsTo().field(handle).unique()` carries through to the explained
  field)
- Reverse M2M edge name collision detection in `validate()`
- Enum default formatting uses `Enum.name` (not `toString()`)
- 35+ tests covering validation, all edge kinds, FKs, indexes, mixins,
  ordering, text rendering, and JSON structure

### Design decisions made during implementation

1. **Separate scan from finalization.** `collectSchemas()` scans the
   classpath without calling `ensureFinalized()`. The inspector commands
   use this so that validation errors flow through
   `SchemaInspector.validate()`'s try-catch and produce clean
   diagnostics, rather than surfacing as raw `JavaExec` failures.

2. **Effective onDelete displayed.** When a `belongsTo` edge has no
   explicit `onDelete`, the explain output shows the effective default
   (RESTRICT for required, SET_NULL for optional) — matching what the
   Postgres driver actually emits.

3. **Synthesized indexes included.** The Indexes section includes both
   explicit `index(...)` declarations and synthesized
   `idx_<table>_<col>_unique` indexes for unique columns, so the output
   shows all indexes that will actually exist in the database.

4. **`validate()` returns rather than throws.** Returns a
   `ValidationResult(valid, errors)` so callers get structured output.
   `explain()` calls `validate()` first and throws on invalid schemas
   since it cannot produce a meaningful graph from a broken schema set.

5. **Inverse resolution is best-effort for display.** `hasMany` and
   `hasOne` edges with no resolvable inverse fail validation and block
   explain output. For edges that pass validation, `tryFindInverseName`
   catches errors and returns null so that the `inverse=` annotation is
   omitted rather than crashing the renderer.

6. **`InspectMain` lives in the postgres module.** The SQL format needs
   the full DDL pipeline (`NormalizedSchema`, `PostgresSqlRenderer`,
   `PostgresTypeMapper`), which lives in postgres. The codegen module
   cannot depend on postgres, so the CLI entry point moved there.

7. **JSON rendering without a serialization library.** `renderJson()`
   builds JSON via string construction to avoid adding a dependency.
   Output is deterministic (stable key order, consistent formatting).

8. **Enum defaults use `Enum.name`.** `formatDefault()` uses the
   enum constant's `name` property rather than `toString()`, since
   `toString()` can be overridden and diverge from the persisted value.

## Summary

Add a shared schema inspection layer that can:

- finalize and validate a schema graph
- explain the resolved relational shape in human-readable form
- emit a normalized machine-readable representation of that shape

This feature is meant to become the common preflight boundary for:

- code generation
- migration planning
- schema debugging
- sample-project inspection

Example commands:

```bash
./gradlew validateEntSchemas
./gradlew explainEntSchemas
./gradlew explainEntSchemas -Pformat=json
./gradlew explainEntSchemas -Pformat=sql
./gradlew explainEntSchemas -Pfilter=Post
./gradlew explainEntSchemas -Pfilter=posts
```

Example output:

```text
Schema: Post
Table: posts
Id: LONG (AUTO_LONG)

Fields:
  title       STRING   NOT NULL
  published   BOOL     NOT NULL DEFAULT false

Foreign Keys:
  author_id -> users.id NOT NULL onDelete=RESTRICT sourceEdge=author

Edges:
  author  belongsTo User   fk=author_id inverse=posts

Indexes:
  idx_posts_author              (author_id)
  idx_posts_author_id_unique    (author_id) unique
```

## Motivation

The current schema DSL has become much more expressive:

- typed field handles
- property-reference-based inverse and `through(...)` linkage
- explicit indexes and FK reuse
- host-bound mixins
- a two-phase declaration/finalization model

That improves authoring, but it also creates a new visibility problem:
the final relational shape is no longer always obvious from reading the
Kotlin declarations alone.

Today, the most important questions are answered only indirectly:

- Which columns actually end up on the table?
- Which FKs are synthesized vs reused from `.field(...)`?
- Which indexes are emitted after mixins and FK helpers are applied?
- Which `manyToMany(...).through(...)` relationships resolved successfully?
- Why did finalization reject a particular inverse or target?

The project needs one explicit schema inspection boundary that is:

- earlier than codegen output review
- more concrete than the raw DSL
- shared across codegen and migrations

## Goals

- Provide one finalized, inspectable view of the schema graph.
- Fail fast on schema graph errors before codegen or migration planning.
- Make table/column/FK/index shape visible without reading generated code.
- Reuse one interpretation of the schema DSL across tooling.
- Support both human-readable and machine-readable output.

## Non-Goals

- Do not apply migrations.
- Do not require a live database.
- Do not introspect an existing database in the first version.
- Do not replace migration diffs.
- Do not redesign the schema DSL in this RFC.
- Do not preserve legacy snapshot compatibility as a primary concern.

## Implemented Surface

### Gradle Tasks

Two plugin tasks:

```bash
./gradlew validateEntSchemas
./gradlew explainEntSchemas
```

Behavior:

- scan configured schema classes (via `collectSchemas`)
- for validate: finalize, validate, print pass/fail diagnostics
- for explain: finalize, validate, build explained graph, print text
- exit code 1 on validation failure or no schemas found

### Programmatic API

```kotlin
object SchemaInspector {
    fun validate(inputs: List<SchemaInput>): ValidationResult
    fun explain(inputs: List<SchemaInput>): ExplainedSchemaGraph
    fun renderText(graph: ExplainedSchemaGraph): String
}
```

The inspector consumes the same `SchemaInput` used by codegen and
migrations. Inputs can be unfinalized — the inspector handles
finalization internally.

### CLI Entry Point

`InspectMain` (`entkt.postgres.InspectMainKt`) accepts:

- `validate` (default) — run validation, print diagnostics
- `explain [--format=text|json|sql] [--filter=<name>]` — print the
  explained output in the requested format, optionally filtered to
  schemas matching the filter (by schema name or table name,
  case-insensitive substring match)

Both Gradle tasks delegate to this entry point via `JavaExec`.
The `explainEntSchemas` task forwards `-Pformat=` and `-Pfilter=`
Gradle properties as CLI flags.

## Validation Scope

Validation happens after schema collection and finalization. The
`validate()` method wraps all of the following in a try-catch and
returns structured `ValidationResult`:

### Hard Errors

The inspector rejects:

- unresolved target schemas
- duplicate schema classes in one graph
- stale pre-finalized schemas reused against a different registry
- invalid inverse targets
- inverse cardinality mismatches
- invalid `through(...)` references
- computed-getter declaration properties used in inverse/through refs
- duplicate field names
- duplicate edge names
- duplicate index names
- duplicate semantic indexes
- `.field(handle)` using a field from another schema
- `index(...)` using framework-owned handles from another schema
- invalid `ON DELETE` / nullability combinations
- reserved generated-member name collisions
- relation-name collisions (tables, indexes, synthesized unique indexes)
- ambiguous M2M junction edges
- missing inverse for hasMany/hasOne edges

### Warnings

The current version does not have a warnings model.

Validation is binary:

- valid
- invalid, with structured diagnostics

Warnings can be added later if there is a real use case.

## Explain Output

### Text Output

The text renderer produces an aligned, readable summary per schema:

```text
Schema: Post
Table: posts
Id: LONG (AUTO_LONG)

Fields:
  title       STRING   NOT NULL
  created_at  TIME     NOT NULL immutable
  deleted_at  TIME     NULL

Foreign Keys:
  author_id -> users.id NOT NULL onDelete=RESTRICT sourceEdge=author

Edges:
  author  belongsTo User   fk=author_id inverse=posts
  tags    manyToMany Tag   through=post_tags(post, tag)

Indexes:
  idx_posts_author              (author_id)
  idx_posts_deleted_at          (deleted_at)
  idx_posts_author_id_unique    (author_id) unique
```

Empty sections (no fields, no FKs, no edges, no indexes) are omitted.

Field modifiers shown after the nullability annotation: `immutable`,
`sensitive`, `unique`, `DEFAULT <value>`.

The Indexes section includes both explicit `index(...)` declarations
and synthesized `idx_<table>_<col>_unique` indexes for unique columns.

### JSON Output

`renderJson()` produces deterministic JSON without a serialization
library dependency:

```json
{
  "schemas": [
    {
      "schemaName": "Post",
      "tableName": "posts",
      "id": {
        "type": "LONG",
        "strategy": "AUTO_LONG"
      },
      "fields": [...],
      "foreignKeys": [...],
      "edges": [...],
      "indexes": [...]
    }
  ]
}
```

The output is stable enough for committed snapshots and diffing.

### SQL Output

`--format=sql` runs the full DDL pipeline (`buildEntitySchemas()` →
`NormalizedSchema.fromEntitySchemas()` → `PostgresSqlRenderer`) and
prints the CREATE TABLE, index, and foreign key statements that would
be emitted for a fresh database:

```sql
-- Schema: Post

CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    title TEXT NOT NULL,
    author_id BIGINT NOT NULL REFERENCES users(id)
);
CREATE INDEX idx_posts_author ON posts (author_id);
```

## Explained Model

The explained model is separate from generated runtime metadata. It is
normalized and inspection-oriented.

```kotlin
data class ExplainedSchemaGraph(
    val schemas: List<ExplainedSchema>,
)

data class ExplainedSchema(
    val schemaName: String,
    val tableName: String,
    val id: ExplainedId,
    val fields: List<ExplainedField>,
    val foreignKeys: List<ExplainedForeignKey>,
    val edges: List<ExplainedEdge>,
    val indexes: List<ExplainedIndex>,
)

data class ExplainedId(val type: FieldType, val strategy: String)

data class ExplainedField(
    val name: String, val type: FieldType, val nullable: Boolean,
    val unique: Boolean, val immutable: Boolean, val sensitive: Boolean,
    val default: String?, val comment: String?,
)

data class ExplainedForeignKey(
    val column: String, val targetTable: String, val targetColumn: String,
    val nullable: Boolean, val onDelete: String, val sourceEdge: String,
)

data class ExplainedEdge(
    val name: String, val kind: String, val targetSchema: String,
    val fkColumn: String?, val inverse: String?,
    val through: ExplainedThrough?, val comment: String?,
)

data class ExplainedThrough(
    val junctionTable: String, val sourceEdge: String, val targetEdge: String,
)

data class ExplainedIndex(
    val name: String, val columns: List<String>,
    val unique: Boolean, val where: String?,
)

data class ValidationResult(val valid: Boolean, val errors: List<String>)
```

## Relationship Coverage

The explain layer makes the relationship matrix obvious:

- one-to-one
- one-to-many
- many-to-many
- same-type variants
- bidirectional traversal declarations

In particular:

- `belongsTo(...)` clearly owns the FK (shown with `fk=` in edges,
  dedicated entry in Foreign Keys section)
- `hasMany(...)` / `hasOne(...)` appear as traversal metadata only
  (no `fk=`, inverse resolved when available)
- `manyToMany(...).through(...)` shows the resolved junction table and
  the two junction edge names used for the join
- `.field(handle)` shows that the FK reused an existing field instead of
  synthesizing one (FK column matches the declared field name)

## Ordering Guarantees

Output preserves declaration order:

- schemas in input order
- fields in declaration order
- edges in declaration order
- explicit indexes in declaration order, then synthesized indexes

This is important for:

- readability
- golden tests
- migration debugging

## Relationship To Existing Tooling

This feature shares the same schema interpretation path as codegen
and migrations.

Flow:

```text
Kotlin schemas
  -> schema scan / collection (collectSchemas)
  -> finalization + validation (ensureFinalized)
  -> normalized schema graph
  -> codegen
  -> migrations
  -> explain / print
```

The `collectSchemas()` function separates scanning from finalization
so that the inspector can control finalization inside its own error
handling. `scanForSchemas()` calls `collectSchemas()` then
`ensureFinalized()` for the codegen/migration path.

### Relationship To Schema Printer

This RFC is broader than [Schema Printer](schema-printer.md).

The likely long-term direction is:

- `Schema Validation And Explain` becomes the shared inspection layer
- `Schema Printer` becomes one renderer or task built on top of that layer

The two should not evolve as independent schema interpreters.

## Implementation Status

### Phase 1 — Done

Internal inspection layer:

- `SchemaInspector` object with `validate()`, `explain()`, `renderText()`,
  `renderJson()`
- `ExplainedSchema` data model in `ExplainedSchema.kt`
- `collectSchemas()` extracted from `scanForSchemas()`
- Text rendering (markdown-style tables)
- JSON rendering (deterministic, no serialization library)
- 35+ tests in `SchemaInspectorTest`

### Phase 2 — Done

Tooling entry points:

- [x] Gradle tasks (`validateEntSchemas`, `explainEntSchemas`)
- [x] CLI entry point (`InspectMain` in postgres module)
- [x] JSON output (`--format=json`)
- [x] SQL output (`--format=sql`)
- [x] Optional schema/table filters (`--filter=<name>`)
- [x] `-Pformat=` and `-Pfilter=` property forwarding in Gradle tasks

### Phase 3 — Done

Consolidation:

- [x] `GenerateMain` and `PlanMigrationMain` use `collectSchemas()` +
  `SchemaInspector.validate()` as the shared validation boundary,
  producing structured diagnostics instead of raw stacktraces
- [ ] use explained graph output for snapshot/golden tests (deferred)

## Test Coverage

`SchemaInspectorTest` covers:

- validation pass/fail (valid graph, duplicate tables, unresolved inverse,
  reverse M2M edge name collisions)
- id type and strategy (AUTO_LONG, CLIENT_UUID, AUTO_INT)
- fields (types, modifiers, nullable, unique, defaults, enum defaults)
- edge-driven uniqueness carried onto reused FK fields
- foreign keys (synthesized, explicit `.field()`, onDelete
  CASCADE/RESTRICT/SET_NULL, nullable)
- edges (belongsTo with FK + inverse, hasMany, hasOne, manyToMany with
  through)
- indexes (explicit, synthesized unique-column, partial WHERE, mixin
  field refs, unique edge FK)
- mixin field inclusion
- input ordering preservation
- text rendering format and empty-section omission
- JSON structure and content

## Open Questions

1. ~~Should `validateEntSchemas` print only diagnostics, or also print a
   summary of the successfully finalized graph?~~
   **Resolved:** prints a one-line summary on success
   (`Schema validation passed (N schemas)`), full diagnostics on failure.

2. ~~Should the first JSON shape align with `EntitySchema` and migration
   metadata closely, or should it be more inspection-specific even if that
   means an extra translation step?~~
   **Resolved:** inspection-specific. The `ExplainedSchema` model is a
   normalized view designed for readability and diffing, not a mirror of
   internal codegen metadata.

3. Should this feature subsume the existing schema-printer concept
   completely, or should the repo keep both docs and let implementation
   merge them later?
