# Schema

Schemas are the source of truth for your data model. Each schema is a
Kotlin `object` that extends `EntSchema` and declares its ID strategy,
fields, edges, indexes, and mixins.

```kotlin
object User : EntSchema() {
    override fun id() = EntId.uuid()
    override fun mixins() = listOf(TimestampMixin)
    override fun fields() = fields { ... }
    override fun edges() = edges { ... }
    override fun indexes() = indexes { ... }
}
```

## ID Strategies

Every schema has a primary key. The `id()` method controls how it's
generated:

| Strategy | Kotlin type | SQL type (Postgres) | Assignment |
|----------|------------|---------------------|------------|
| `EntId.int()` | `Int` | `serial` | Auto-increment |
| `EntId.long()` | `Long` | `bigserial` | Auto-increment |
| `EntId.uuid()` | `UUID` | `uuid` | Client-generated on create |
| `EntId.string()` | `String` | `text` | Caller-provided |

The default is `EntId.int()` if `id()` is not overridden.

## Fields

Fields are declared inside a `fields { }` block using type-specific
builder methods:

```kotlin
override fun fields() = fields {
    string("name").minLen(1).maxLen(64)
    text("body")
    bool("active").default(true)
    int("count").positive()
    long("big_number")
    float("score")
    double("precise_score")
    time("created_at").immutable()
    uuid("external_id")
    bytes("data")
    enum("status").values("DRAFT", "PUBLISHED", "ARCHIVED")
    enum<Priority>("priority").default(Priority.LOW)  // typed enum
}
```

### Field Types

| Builder | `FieldType` | Kotlin type | Postgres type |
|---------|------------|-------------|---------------|
| `string()` | `STRING` | `String` | `text` |
| `text()` | `TEXT` | `String` | `text` |
| `bool()` | `BOOL` | `Boolean` | `boolean` |
| `int()` | `INT` | `Int` | `integer` |
| `long()` | `LONG` | `Long` | `bigint` |
| `float()` | `FLOAT` | `Float` | `real` |
| `double()` | `DOUBLE` | `Double` | `double precision` |
| `time()` | `TIME` | `Instant` | `timestamptz` |
| `uuid()` | `UUID` | `UUID` | `uuid` |
| `bytes()` | `BYTES` | `ByteArray` | `bytea` |
| `enum()` | `ENUM` | `String` | `text` |
| `enum<E>()` | `ENUM` | `E` | `text` |

### Common Modifiers

These are available on all field types:

| Modifier | Effect |
|----------|--------|
| `.nullable()` | Field is nullable in the generated code |
| `.unique()` | Adds a unique constraint |
| `.immutable()` | Omitted from update builder setters |
| `.sensitive()` | Excluded from string representations |
| `.default(value)` | Default value for creates |
| `.updateDefaultNow()` | Set to `Instant.now()` on every update (TIME fields only) |
| `.comment(text)` | Documentation comment |
| `.storageKey(name)` | Override the database column name |

### Validators

String fields:

```kotlin
string("name").minLen(1).maxLen(100).notEmpty()
string("slug").match(Regex("^[a-z0-9-]+$"))
```

Numeric fields (`int`, `long`, `float`, `double`):

```kotlin
int("age").min(0).max(150)
int("quantity").positive()
int("balance").nonNegative()
double("temperature").negative()
```

Validators are enforced as inline checks in the generated `save()` methods.
They throw `IllegalArgumentException` if the value is invalid.

### Typed Enums

Use the reified `enum<E>()` builder to bind a field to a Kotlin enum class.
The generated entity, create builder, update builder, and query column
references all use the actual enum type instead of `String`:

```kotlin
enum class Priority { LOW, MEDIUM, HIGH }

object Ticket : EntSchema() {
    override fun fields() = fields {
        string("title")
        enum<Priority>("priority").default(Priority.LOW)
    }
}
```

With this declaration:

- The generated `Ticket` entity has `val priority: Priority`
- The create/update builders have `var priority: Priority?`
- Query predicates accept enum values: `Ticket.priority eq Priority.HIGH`
- The `.default()` method requires a constant from the same enum class —
  passing a value from a different enum (e.g. `OtherEnum.FOO`) is rejected
  at codegen time

Values are stored as strings in the database (via `.name`) and converted
back with `valueOf()` when reading rows. The driver layer is unchanged.

The untyped `enum("field").values("A", "B")` form is still supported for
cases where a Kotlin enum class isn't available.

## Edges

Edges define relationships between entities. They are declared inside an
`edges { }` block:

```kotlin
override fun edges() = edges {
    to("posts", Post)                              // one-to-many
    from("author", User).ref("posts").unique()     // many-to-one (inverse)
}
```

### One-to-Many

`to(name, target)` declares the "one" side. No FK column is added to this
entity -- the FK lives on the target.

```kotlin
// User schema
override fun edges() = edges {
    to("posts", Post)  // User has many Posts
}
```

### Many-to-One / One-to-One

`from(name, target)` declares the "many" or "belongs-to" side. This
synthesizes a FK column (e.g. `author_id`) on the current entity.

```kotlin
// Post schema
override fun edges() = edges {
    from("author", User).ref("posts").unique().required()
}
```

| Modifier | Effect |
|----------|--------|
| `.ref(name)` | Names the inverse edge on the target schema |
| `.unique()` | Makes this a one-to-one relationship |
| `.required()` | FK column is NOT NULL |
| `.field(name)` | Override the FK column name |
| `.onDelete(action)` | Set the FK `ON DELETE` action (see below) |

### ON DELETE Actions

By default, FK columns use `ON DELETE SET NULL` (nullable) or
`ON DELETE RESTRICT` (required). Use `.onDelete()` to override:

```kotlin
override fun edges() = edges {
    from("owner", Owner).unique().required().onDelete(OnDelete.CASCADE)
}
```

| Action | Effect |
|--------|--------|
| `OnDelete.CASCADE` | Delete child rows when the parent is deleted |
| `OnDelete.SET_NULL` | Set the FK column to NULL (only valid on optional edges) |
| `OnDelete.RESTRICT` | Prevent deletion of the parent while children exist |

Both `PostgresDriver` and `InMemoryDriver` enforce these actions.
The migration system detects changes to `onDelete` and generates the
appropriate `DROP CONSTRAINT` / `ADD CONSTRAINT` ops.

### Many-to-Many

Use `.through()` to declare an M2M relationship via a junction table:

```kotlin
// User schema
override fun edges() = edges {
    to("groups", Group).through("user_groups", UserGroup)
}
```

The junction schema (`UserGroup`) is itself an `EntSchema` with two
`from()` edges pointing at the two sides.

For ambiguous junction tables (where both sides point to the same entity
type), use `sourceEdge` and `targetEdge` to disambiguate:

```kotlin
to("friends", User).through(
    "friendships", Friendship,
    sourceEdge = "user", targetEdge = "friend"
)
```

## Indexes

Composite indexes are declared in an `indexes { }` block:

```kotlin
override fun indexes() = indexes {
    index("name", "email").unique()
    index("created_at")
    index("status", "priority").storageKey("idx_status_priority")
}
```

Single-column unique constraints are simpler -- just use `.unique()` on the
field directly. Composite indexes that need a custom database name use
`.storageKey()`.

### Partial indexes

Partial (conditional) indexes include only rows matching a `WHERE` predicate:

```kotlin
override fun indexes() = indexes {
    index("email").unique().where("active = true")
}
```

This generates `CREATE UNIQUE INDEX ... ON users (email) WHERE active = true`.
Partial indexes are useful for enforcing uniqueness on a subset of rows or
speeding up queries that always filter by a condition.

When two indexes share the same columns and uniqueness but differ only by
predicate, entkt derives distinct index names automatically (using a hash
of the WHERE clause). You can also use `.storageKey()` to set explicit names.

**Predicate normalization:** PostgreSQL's catalog deparses predicates
differently from the user-written form (adding outer parentheses, type
casts, etc.). The migration differ normalizes both sides before comparing,
so `active = true` and `((active)::boolean = true)` are treated as
equivalent. For very exotic expressions where normalization falls short,
pin the index with `.storageKey()` to avoid spurious diffs.

## Mixins

Mixins are reusable groups of fields and indexes that can be
included in multiple schemas:

```kotlin
object TimestampMixin : EntMixin {
    override fun fields() = fields {
        time("created_at").immutable()
        time("updated_at")
    }
}

object User : EntSchema() {
    override fun mixins() = listOf(TimestampMixin)
    // ...
}
```

Mixin fields are merged into the schema's field list during code generation.
The generated entity class, create builder, and update builder all include
the mixin fields. Immutable mixin fields (like `created_at` above) are
omitted from the update builder.
