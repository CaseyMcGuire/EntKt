# Schema

Schemas are the source of truth for your data model. Each schema is a
Kotlin `class` that extends `EntSchema` with an explicit table name,
and declares its fields, edges, and indexes as plain property
declarations.

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.uuid()

    val name = string("name").minLen(1).maxLen(64)
    val email = string("email").unique()

    val posts = hasMany<Post>("posts")
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

The `id()` method is abstract, so every schema must override it.

## Fields

Fields are declared as property declarations on the schema class:

```kotlin
class Ticket : EntSchema("tickets") {
    override fun id() = EntId.int()

    val title = string("title").minLen(1).maxLen(200)
    val body = text("body")
    val active = bool("active").default(true)
    val count = int("count").positive()
    val bigNumber = long("big_number")
    val score = float("score")
    val preciseScore = double("precise_score")
    val createdAt = time("created_at").immutable()
    val externalId = uuid("external_id")
    val data = bytes("data")
    val priority = enum<Priority>("priority").default(Priority.LOW)
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
| `enum<E>()` | `ENUM` | `E` | `text` |

### Common Modifiers

These are available on all field types:

| Modifier | Effect |
|----------|--------|
| `.optional()` | Field is nullable in the generated code |
| `.unique()` | Adds a unique constraint |
| `.immutable()` | Omitted from update builder setters |
| `.sensitive()` | Excluded from string representations |
| `.default(value)` | Type-safe default value for creates |
| `.defaultNow()` | Set to `Instant.now()` on create (TIME fields only) |
| `.updateDefaultNow()` | Set to `Instant.now()` on every update (TIME fields only) |
| `.comment(text)` | Documentation comment |

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

### Enums

Enum fields require a Kotlin enum class via the reified `enum<E>()` builder.
The generated entity, create builder, update builder, and query column
references all use the actual enum type:

```kotlin
enum class Priority { LOW, MEDIUM, HIGH }

class Ticket : EntSchema("tickets") {
    override fun id() = EntId.int()

    val title = string("title")
    val priority = enum<Priority>("priority").default(Priority.LOW)
}
```

With this declaration:

- The generated `Ticket` entity has `val priority: Priority`
- The create/update builders have `var priority: Priority?`
- Query predicates accept enum values: `Ticket.priority eq Priority.HIGH`
- The `.default()` method requires a constant from the same enum class —
  passing a value from a different enum (e.g. `OtherEnum.FOO`) is rejected
  at schema construction time

Values are stored as strings in the database (via `.name`) and converted
back with `valueOf()` when reading rows. The driver layer is unchanged.

## Edges

Edges define relationships between entities. They are declared as
property declarations on the schema class:

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()

    val posts = hasMany<Post>("posts")
}

class Post : EntSchema("posts") {
    override fun id() = EntId.long()

    val author = belongsTo<User>("author").inverse(User::posts).required()
}
```

### HasMany / HasOne

`hasMany<Target>(name)` declares the "one" side of a one-to-many
relationship. No FK column is added to this entity — the FK lives on
the target. `hasOne<Target>(name)` is similar but for one-to-one
relationships (the inverse `belongsTo` must have `.unique()`).

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()

    val posts = hasMany<Post>("posts")
}
```

### BelongsTo

`belongsTo<Target>(name)` declares the FK-owning side. This synthesizes
a FK column (e.g. `author_id`) on the current entity.

```kotlin
class Post : EntSchema("posts") {
    override fun id() = EntId.long()

    val author = belongsTo<User>("author").inverse(User::posts).required()
}
```

| Modifier | Effect |
|----------|--------|
| `.inverse(Target::edge)` | Links to the inverse edge on the target schema |
| `.required()` | FK column is NOT NULL |
| `.unique()` | Adds a UNIQUE constraint on the FK column (for 1:1 relationships) |
| `.field(handle)` | Reuse an existing field declaration as the FK column |
| `.onDelete(action)` | Set the FK `ON DELETE` action (see below) |

### ON DELETE Actions

By default, FK columns use `ON DELETE SET NULL` (nullable) or
`ON DELETE RESTRICT` (required). Use `.onDelete()` to override:

```kotlin
class Pet : EntSchema("pets") {
    override fun id() = EntId.int()

    val owner = belongsTo<Owner>("owner").required().onDelete(OnDelete.CASCADE)
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

Use `manyToMany<Target>(...).through<Junction>(...)` to declare an M2M
relationship via a junction table:

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()

    val groups = manyToMany<Group>("groups").through<UserGroup>(UserGroup::user, UserGroup::group)
}
```

The junction schema (`UserGroup`) is itself an `EntSchema` with two
`belongsTo()` edges pointing at the two sides.

For ambiguous junction tables (where both sides point to the same entity
type), the typed property references disambiguate which junction edge
is source vs target:

```kotlin
class Person : EntSchema("people") {
    override fun id() = EntId.long()

    val friends = manyToMany<Person>("friends")
        .through<Friendship>(Friendship::user, Friendship::friend)
}

class Friendship : EntSchema("friendships") {
    override fun id() = EntId.long()

    val user = belongsTo<Person>("user").required()
    val friend = belongsTo<Person>("friend").required()
}
```

## Indexes

Indexes are declared as property declarations using field handles.
For synthesized FK columns, use `.fk` on the edge declaration:

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.int()

    val name = string("name")
    val email = string("email")
    val status = string("status")
    val priority = int("priority")

    val byNameEmail = index("idx_name_email", name, email).unique()
    val byStatus = index("idx_status_priority", status, priority)
}

// FK index using .fk on a belongsTo edge
class Friendship : EntSchema("friendships") {
    override fun id() = EntId.int()

    val requester = belongsTo<User>("requester").required()
    val recipient = belongsTo<User>("recipient").required()

    val idx = index("idx_requester_recipient", requester.fk, recipient.fk).unique()
}
```

Single-column unique constraints are simpler -- just use `.unique()` on the
field directly. The index name is the first argument and is required.

### Partial indexes

Partial (conditional) indexes include only rows matching a `WHERE` predicate:

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.int()

    val email = string("email")
    val active = bool("active").default(true)

    val activeEmail = index("idx_active_email", email).unique().where("active = true")
}
```

This generates `CREATE UNIQUE INDEX ... ON users (email) WHERE active = true`.
Partial indexes are useful for enforcing uniqueness on a subset of rows or
speeding up queries that always filter by a condition.

**Predicate normalization:** PostgreSQL's catalog deparses predicates
differently from the user-written form (adding outer parentheses, type
casts, etc.). The migration differ normalizes both sides before comparing,
so `active = true` and `((active)::boolean = true)` are treated as
equivalent.

## Reusable Mixins

Reusable local field/index bundles can be shared via `EntMixin` and
`include(...)`:

```kotlin
class Timestamps(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt = time("created_at").immutable()
    val updatedAt = time("updated_at")
}

class User : EntSchema("users") {
    override fun id() = EntId.uuid()

    val timestamps = include(::Timestamps)
    val name = string("name")
    // User includes createdAt and updatedAt
}
```

Mixin fields are included in the generated entity class, create builder,
and update builder. Immutable fields (like `createdAt` above) are omitted
from the update builder. Relationship edges stay on the host schema.
