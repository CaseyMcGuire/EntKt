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

## Relationship Patterns

The relationship DSL is centered on one rule:

- `belongsTo(...)` owns the foreign key column
- `hasMany(...)` / `hasOne(...)` are inverse traversal declarations
- `manyToMany(...).through(...)` points at an explicit junction schema

Quick map:

| Pattern | entkt shape | Physical schema result |
|---------|-------------|------------------------|
| `O2O Two Types` | `hasOne` + `belongsTo().unique()` | FK column with `UNIQUE` on the dependent table |
| `O2O Same Type` | self `hasOne` + self `belongsTo().unique()` | self-referencing FK column with `UNIQUE` |
| `O2O Bidirectional` | same as O2O, with inverse declared | same table shape as O2O; both traversals exposed |
| `O2M Two Types` | `hasMany` + `belongsTo()` | FK column on the many-side table |
| `O2M Same Type` | self `hasMany` + self `belongsTo()` | self-referencing FK column on the child rows |
| `M2M Two Types` | `manyToMany().through<Junction>(...)` | explicit junction table with two FKs |
| `M2M Same Type` | self `manyToMany().through<Junction>(...)` | explicit self-junction table with two FKs to the same table |
| `M2M Bidirectional` | matching `manyToMany().through(...)` on both endpoint schemas | same junction table; both traversals exposed |

### O2O Two Types

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.uuid()

    val profile = hasOne<Profile>("profile")
}

class Profile : EntSchema("profiles") {
    override fun id() = EntId.uuid()

    val user = belongsTo<User>("user")
        .inverse(User::profile)
        .required()
        .unique()
}
```

Generated table shape:

- `users`
  - `id UUID PRIMARY KEY`
- `profiles`
  - `id UUID PRIMARY KEY`
  - `user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE RESTRICT`

`belongsTo(...).unique()` is what turns the FK from many-to-one into one-to-one.

### O2O Same Type

```kotlin
class Employee : EntSchema("employees") {
    override fun id() = EntId.long()

    val mentee = hasOne<Employee>("mentee")
    val mentor = belongsTo<Employee>("mentor")
        .inverse(Employee::mentee)
        .unique()
}
```

Generated table shape:

- `employees`
  - `id BIGINT PRIMARY KEY`
  - `mentor_id BIGINT UNIQUE REFERENCES employees(id) ON DELETE SET NULL`

This is the same physical pattern as O2O two types; the FK simply points back
at the same table.

### O2O Bidirectional

In entkt, bidirectional O2O is not a separate builder shape. The normal O2O
pattern is already bidirectional as soon as you declare the inverse:

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.uuid()

    val profile = hasOne<Profile>("profile")
}

class Profile : EntSchema("profiles") {
    override fun id() = EntId.uuid()

    val user = belongsTo<User>("user")
        .inverse(User::profile)
        .unique()
}
```

Result:

- `User` can traverse to `profile`
- `Profile` can traverse to `user`
- the SQL table shape is the same as `O2O Two Types`

### O2M Two Types

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()

    val posts = hasMany<Post>("posts")
}

class Post : EntSchema("posts") {
    override fun id() = EntId.long()

    val author = belongsTo<User>("author")
        .inverse(User::posts)
        .required()
}
```

Generated table shape:

- `users`
  - `id BIGINT PRIMARY KEY`
- `posts`
  - `id BIGINT PRIMARY KEY`
  - `author_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT`

`hasMany(...)` adds no local column. The FK lives on `posts` because
`belongsTo(...)` owns the relationship.

### O2M Same Type

```kotlin
class Category : EntSchema("categories") {
    override fun id() = EntId.long()

    val children = hasMany<Category>("children")
    val parent = belongsTo<Category>("parent")
        .inverse(Category::children)
}
```

Generated table shape:

- `categories`
  - `id BIGINT PRIMARY KEY`
  - `parent_id BIGINT REFERENCES categories(id) ON DELETE SET NULL`

This is the same physical pattern as O2M two types, but recursive.

### M2M Two Types

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()

    val groups = manyToMany<Group>("groups")
        .through<UserGroup>(UserGroup::user, UserGroup::group)
}

class Group : EntSchema("groups") {
    override fun id() = EntId.long()
}

class UserGroup : EntSchema("user_groups") {
    override fun id() = EntId.long()

    val user = belongsTo<User>("user").required()
    val group = belongsTo<Group>("group").required()

    val byUserGroup = index("idx_user_groups_user_group", user.fk, group.fk).unique()
}
```

Generated table shape:

- `users`
  - `id BIGINT PRIMARY KEY`
- `groups`
  - `id BIGINT PRIMARY KEY`
- `user_groups`
  - `id BIGINT PRIMARY KEY`
  - `user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT`
  - `group_id BIGINT NOT NULL REFERENCES groups(id) ON DELETE RESTRICT`
  - `UNIQUE INDEX idx_user_groups_user_group (user_id, group_id)`

`manyToMany(...)` never creates an implicit join table. The junction is always
an explicit `EntSchema`.

### M2M Same Type

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

    val byFriendPair = index("idx_friendships_user_friend", user.fk, friend.fk).unique()
}
```

Generated table shape:

- `people`
  - `id BIGINT PRIMARY KEY`
- `friendships`
  - `id BIGINT PRIMARY KEY`
  - `user_id BIGINT NOT NULL REFERENCES people(id) ON DELETE RESTRICT`
  - `friend_id BIGINT NOT NULL REFERENCES people(id) ON DELETE RESTRICT`

The property references in `through(...)` disambiguate which junction edge is
the source and which is the target.

### M2M Bidirectional

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()

    val groups = manyToMany<Group>("groups")
        .through<Membership>(Membership::user, Membership::group)
}

class Group : EntSchema("groups") {
    override fun id() = EntId.long()

    val users = manyToMany<User>("users")
        .through<Membership>(Membership::group, Membership::user)
}

class Membership : EntSchema("memberships") {
    override fun id() = EntId.long()

    val user = belongsTo<User>("user").required()
    val group = belongsTo<Group>("group").required()
}
```

Result:

- `User` can traverse to `groups`
- `Group` can traverse to `users`
- the SQL table shape is still just `users`, `groups`, and `memberships`
- declaring both sides adds traversal metadata, not extra endpoint columns

## How Table Schema Is Generated

Each `EntSchema` becomes one SQL table plus runtime metadata describing its
columns, foreign keys, edges, and indexes.

Column generation rules:

- `id()` defines the primary key column type and strategy
- declared fields become columns in declaration order
- included mixin fields are inserted where `include(...)` appears
- `belongsTo(...)` adds a foreign key column unless `.field(handle)` reuses an
  existing declared field
- `hasMany(...)`, `hasOne(...)`, and `manyToMany(...)` do not add local columns
  by themselves

Constraint and index rules:

- `.required()` on `belongsTo(...)` makes the FK `NOT NULL`
- `.unique()` on `belongsTo(...)` adds a `UNIQUE` constraint on the FK column
- field-level `.unique()` becomes a single-column unique constraint
- `index("...", ...)` becomes a named secondary index
- `index(...).where(...)` becomes a partial index when the driver supports it

Runtime metadata rules:

- outgoing edges are keyed by the local edge name
- `hasMany(...)` / `hasOne(...)` are traversal-only metadata on the current
  schema
- `belongsTo(...)` contributes both traversal metadata and a local FK column
- `manyToMany(...).through(...)` resolves through the junction schema at
  finalization time and is emitted as join metadata, not as extra columns on the
  endpoint tables

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
