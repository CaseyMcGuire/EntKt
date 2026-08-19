# Schema

Schemas are the source of truth for your data model. Each schema is a
Kotlin `class` that extends `EntSchema` with an explicit table name and
client name, and declares its fields, edges, and indexes as delegated
property declarations.

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val name by string("name").minLength(1).maxLength(64)
    val email by string("email").unique()

    val posts by hasMany<Post>("posts")
}
```

## Names

A schema carries three independent kinds of name. entkt never derives
one from another, and never pluralizes, singularizes, or otherwise
transforms any of them.

| What you write | What it names |
|---|---|
| The schema class name (`User`) | Generated types: `User`, `UserQuery`, `UserRepo`, `UserCreate`, `UserUpdate` |
| `clientName = "users"` | Generated client and configuration properties: `client.users`, `privacy.users { }`, `validation.users { }`, `hooks.users { }` |
| The delegated `val` (`val email by …`) | Every generated field or edge API: `User.email`, `user.email`, `create.email` |
| The string argument (`string("email")`, `hasMany<Post>("posts")`) | Storage only: columns, tables, indexes, constraints, joins, migration identity |

Because these are independent, renames have independent consequences:

- rename the `val` → the Kotlin API changes, the database does not;
- rename the storage string → a migration, but no Kotlin source change;
- rename `clientName` → `client.<name>` changes, entity types and the
  table do not.

That independence is the point. A column named for a legacy system can
carry a domain-appropriate Kotlin name:

```kotlin
class Article : EntSchema("legacy_article_tbl", clientName = "stories") {
    override fun id() = EntId.long()

    val publicTitle by string("legacy_title_txt")
    val writer by belongsTo<User>("primary_author")
}
```

This generates `Article.publicTitle`, `article.writer`,
`queryWriter()`, and `client.stories` — never `legacyTitleTxt` or
`primaryAuthor`.

### `clientName` is required

There is no default and no inference. Supply the exact lower-camel
property name application code should read:

```kotlin
class Person : EntSchema("people", clientName = "people")
class NewsItem : EntSchema("news_items", clientName = "news")
class Audit : EntSchema("audit_log", clientName = "audit")
```

Irregular, uncountable, singular, acronym, and non-English names all
work, because nothing inspects the word. `client.people`,
`client.news`, and `client.audit` are emitted exactly as written.

### `by` marks a generated API name

Fields and edges are declared with Kotlin property delegation. The `by`
keyword is what hands entkt the property name, and it tells the reader
that the name is a public commitment rather than an incidental local:

```kotlin
val displayName by string("display_name")   // ✅ names Article.displayName
val displayName = string("display_name")    // ❌ rejected: names nothing
```

An ordinary `=` still constructs a builder, so the column would exist
while no generated API pointed at it. Schema finalization rejects that
with a message naming the field. Also rejected: non-public declarations
(a `private val` must not create a public generated member), binding one
builder to two properties, wrapper delegates such as `by lazy { … }`,
computed getters, and declarations inherited from a superclass. A
delegated `var` does not compile at all — builders have no `setValue`.

Index declarations keep `=`, because an index name is storage metadata
and generates no member of its own:

```kotlin
val byEmail = index("idx_user_email", email).unique()
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
class Ticket : EntSchema("tickets", clientName = "tickets") {
    override fun id() = EntId.int()

    val title by string("title").minLength(1).maxLength(200)
    val body by text("body")
    val active by bool("active").default(true)
    val count by int("count").positive()
    val bigNumber by long("big_number")
    val score by float("score")
    val preciseScore by double("precise_score")
    val createdAt by time("created_at").immutable()
    val externalId by uuid("external_id")
    val data by bytes("data")
    val priority by enum<Priority>("priority").default(Priority.LOW)
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

Postgres-specific native types (e.g. `pgvector`) are import-gated and not on the
base DSL -- see [Native Column Types (Postgres pgvector)](#native-column-types-postgres-pgvector).
Typed JSON columns (a `@Serializable` type — including generic shapes like
`List<Rect>` — stored as `jsonb`) are declared with `json(...)` -- see
[Typed JSON Fields](#typed-json-fields-postgres-jsonb).

### Common Modifiers

These are available on all field types:

| Modifier | Effect |
|----------|--------|
| `.nullable()` | Field is nullable; generated as a Kotlin `T?` |
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
string("name").minLength(1).maxLength(100).notEmpty()
string("slug").match(Regex("^[a-z0-9-]+$"))
```

Numeric fields (`int`, `long`, `float`, `double`):

```kotlin
int("age").min(0).max(150)
int("quantity").positive()
int("balance").nonNegative()
double("temperature").negative()
```

The generated create and update save terminals enforce these validators.
A failure is `MutationResult.Failed(EntValidationException)` whose
`violations` carry structured, field-named `ValidationViolation` entries;
`.getOrThrow()` throws that stored exception.
These validators are not database constraints: for example,
`maxLength(255)` still creates a `text` column rather than `varchar(255)` or
a `CHECK` constraint. Writes made outside entkt must enforce the same
invariants separately.

### Enums

Enum fields require a Kotlin enum class via the reified `enum<E>()` builder.
The generated entity, create builder, update builder, and query column
references all use the actual enum type:

```kotlin
enum class Priority { LOW, MEDIUM, HIGH }

class Ticket : EntSchema("tickets", clientName = "tickets") {
    override fun id() = EntId.int()

    val title by string("title")
    val priority by enum<Priority>("priority").default(Priority.LOW)
}
```

With this declaration:

- The generated `Ticket` entity has `val priority: Priority`
- The create/update builders have `var priority: Priority?`
- Query predicates accept enum values: `Ticket.priority eq Priority.HIGH`
- The `.default()` method requires a constant from the same enum class —
  passing a value from a different enum (e.g. `OtherEnum.FOO`) is rejected
  at schema construction time

Values are stored using the enum constant's name and returned as the declared
enum type.

> **Caveat: renaming an enum constant is a data migration, not a refactor.**
> Because values persist as the constant's `.name` in a plain `text` column
> (no native DB enum, no `CHECK` constraint), renaming a *constant* (e.g.
> `MEDIUM` → `NORMAL`) diverges from existing data:
>
> - Existing rows keep the old string, so `valueOf("MEDIUM")` **throws** when
>   those rows are read back.
> - If the constant was a `.default(...)`, migration generation only updates
>   the database default to `NORMAL`. It does **not**
>   rewrite existing rows, and neither the database nor the diff tool flags
>   the stale values — the migration applies cleanly and the problem only
>   surfaces later, at read time.
>
> Renaming the enum *class* is safe (the persisted value is unchanged). To
> rename a *constant*, write a manual migration that backfills the data
> (`UPDATE … SET col = 'NORMAL' WHERE col = 'MEDIUM'`) alongside the code
> change.

## Edges

Edges define relationships between entities. They are declared as
property declarations on the schema class:

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.long()

    val posts by hasMany<Post>("posts")
}

class Post : EntSchema("posts", clientName = "posts") {
    override fun id() = EntId.long()

    val author by belongsTo<User>("author").inverse(User::posts)
}
```

### HasMany / HasOne

`hasMany<Target>(name)` declares the "one" side of a one-to-many
relationship. No FK column is added to this entity — the FK lives on
the target. `hasOne<Target>(name)` is similar but for one-to-one
relationships (the inverse `belongsTo` must have `.unique()`).

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.long()

    val posts by hasMany<Post>("posts")
}
```

### BelongsTo

`belongsTo<Target>(name)` declares the FK-owning side. This synthesizes
a FK column (e.g. `author_id`) on the current entity. Relationships are
required-by-default; add `.nullable()` to make the FK nullable.

```kotlin
class Post : EntSchema("posts", clientName = "posts") {
    override fun id() = EntId.long()

    val author by belongsTo<User>("author").inverse(User::posts)
}
```

| Modifier | Effect |
|----------|--------|
| `.inverse(Target::edge)` | Links to the inverse edge on the target schema |
| `.nullable()` | FK column is nullable (default is NOT NULL) |
| `.unique()` | Adds a UNIQUE constraint on the FK column (for 1:1 relationships) |
| `.field(handle)` | Reuse an existing field declaration as the FK column |
| `.onDelete(action)` | Set the FK `ON DELETE` action (see below) |

### ON DELETE Actions

By default, FK columns use `ON DELETE SET NULL` (nullable) or
`ON DELETE RESTRICT` (required). Use `.onDelete()` to override:

```kotlin
class Pet : EntSchema("pets", clientName = "pets") {
    override fun id() = EntId.int()

    val owner by belongsTo<Owner>("owner").onDelete(OnDelete.CASCADE)
}
```

| Action | Effect |
|--------|--------|
| `OnDelete.CASCADE` | Delete child rows when the parent is deleted |
| `OnDelete.SET_NULL` | Set the FK column to NULL (only valid on nullable edges) |
| `OnDelete.RESTRICT` | Prevent deletion of the parent while children exist |

`PostgresDriver` enforces these actions via `REFERENCES ... ON DELETE`
in the generated DDL. The migration system detects changes to
`onDelete` and generates the appropriate `DROP CONSTRAINT` / `ADD
CONSTRAINT` ops.

### Many-to-Many

M2M relationships are declared by picking a write model at the schema
site — the junction can either be a **domain entity** mutated through
its own repo, or a **pure link table** that direct edge helpers can
write to. Pick the write model with one of two markers:

| Marker | When to use |
|---|---|
| `.throughEntity<Junction>(sourceEdge, targetEdge)` | Junction carries payload, hooks, privacy, or validation. Callers mutate it through the generated junction repo (e.g. `client.userGroups.create { ... }.save()`). |
| `.throughLink<Junction>(sourceEdge, targetEdge)` | Junction is pure relationship storage (id + the two FK columns; no payload, no hooks, no privacy). Generated update builders get direct id-only edge helpers: `tags.add(tagId)` / `tags.remove(tagId)` / `tags.set(listOf(...))`. See [Edges → Many-to-Many](03-edges.md#many-to-many) for the full mutator API and the transaction/capability requirements. |

The two refs always name the junction's `belongsTo` edges in the
order **source first, target second** — `sourceEdge` points back at
the schema declaring the `manyToMany`; `targetEdge` points at the
M2M target (`<Target>` in `manyToMany<Target>`). Schema finalization
rejects refs that don't match this orientation.

The throughEntity case (most domain models start here):

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.long()

    val groups by manyToMany<Group>("groups")
        .throughEntity<UserGroup>(UserGroup::user, UserGroup::group)
}
```

The junction schema (`UserGroup`) is itself an `EntSchema` with two
`belongsTo()` edges pointing at the two sides — and any payload fields
the relationship needs (e.g. `joinedAt`, `role`).

For ambiguous junction tables (where both sides point to the same
entity type), the typed property references disambiguate which
junction edge is source vs target:

```kotlin
class Person : EntSchema("people", clientName = "persons") {
    override fun id() = EntId.long()

    val friends by manyToMany<Person>("friends")
        .throughEntity<Friendship>(Friendship::user, Friendship::friend)
}

class Friendship : EntSchema("friendships", clientName = "friendships") {
    override fun id() = EntId.long()

    val user by belongsTo<Person>("user")
    val friend by belongsTo<Person>("friend")
}
```

A `throughLink` declaration is rejected during generation unless the junction
has an id, exactly the two named non-null `belongsTo` fields, explicit
`OnDelete.CASCADE` on both edges, a generated id strategy, and a non-partial
unique composite index on the two FK columns in either order. A relationship
declared from both endpoints also needs a non-partial index leading with each
endpoint's source FK. The foreign-key fields cannot have write-time modifiers.
Junctions that need payload columns, hooks, or validators must use
`throughEntity`.

## Relationship Patterns

The relationship DSL is centered on one rule:

- `belongsTo(...)` owns the foreign key column
- `hasMany(...)` / `hasOne(...)` are inverse traversal declarations
- `manyToMany(...).throughEntity(...)` (or `.throughLink(...)`) points at an explicit junction schema

Quick map:

| Pattern | entkt shape | Physical schema result |
|---------|-------------|------------------------|
| `O2O Two Types` | `hasOne` + `belongsTo().unique()` | FK column with `UNIQUE` on the dependent table |
| `O2O Same Type` | self `hasOne` + self `belongsTo().unique()` | self-referencing FK column with `UNIQUE` |
| `O2O Bidirectional` | same as O2O, with inverse declared | same table shape as O2O; both traversals exposed |
| `O2M Two Types` | `hasMany` + `belongsTo()` | FK column on the many-side table |
| `O2M Same Type` | self `hasMany` + self `belongsTo()` | self-referencing FK column on the child rows |
| `M2M Two Types` | `manyToMany().throughEntity<Junction>(...)` (or `.throughLink<Junction>(...)`) | explicit junction table with two FKs |
| `M2M Same Type` | self `manyToMany().throughEntity<Junction>(...)` (or `.throughLink<Junction>(...)`) | explicit self-junction table with two FKs to the same table |
| `M2M Bidirectional` | matching `manyToMany().throughEntity(...)` or `.throughLink(...)` declarations with pair-swapped orientation keys | same junction table; both endpoints declare their own forward traversal |

### O2O Two Types

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val profile by hasOne<Profile>("profile")
}

class Profile : EntSchema("profiles", clientName = "profiles") {
    override fun id() = EntId.uuid()

    val user by belongsTo<User>("user")
        .inverse(User::profile)
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
class Employee : EntSchema("employees", clientName = "employees") {
    override fun id() = EntId.long()

    val mentee by hasOne<Employee>("mentee")
    val mentor by belongsTo<Employee>("mentor")
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
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val profile by hasOne<Profile>("profile")
}

class Profile : EntSchema("profiles", clientName = "profiles") {
    override fun id() = EntId.uuid()

    val user by belongsTo<User>("user")
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
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.long()

    val posts by hasMany<Post>("posts")
}

class Post : EntSchema("posts", clientName = "posts") {
    override fun id() = EntId.long()

    val author by belongsTo<User>("author")
        .inverse(User::posts)
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
class Category : EntSchema("categories", clientName = "categories") {
    override fun id() = EntId.long()

    val children by hasMany<Category>("children")
    val parent by belongsTo<Category>("parent")
        .inverse(Category::children)
        .nullable()
}
```

Generated table shape:

- `categories`
  - `id BIGINT PRIMARY KEY`
  - `parent_id BIGINT REFERENCES categories(id) ON DELETE SET NULL`

This is the same physical pattern as O2M two types, but recursive.

### M2M Two Types

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.long()

    val groups by manyToMany<Group>("groups")
        .throughEntity<UserGroup>(UserGroup::user, UserGroup::group)
}

class Group : EntSchema("groups", clientName = "groups") {
    override fun id() = EntId.long()
}

class UserGroup : EntSchema("user_groups", clientName = "userGroups") {
    override fun id() = EntId.long()

    val user by belongsTo<User>("user")
    val group by belongsTo<Group>("group")

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
class Person : EntSchema("people", clientName = "persons") {
    override fun id() = EntId.long()

    val friends by manyToMany<Person>("friends")
        .throughEntity<Friendship>(Friendship::user, Friendship::friend)
}

class Friendship : EntSchema("friendships", clientName = "friendships") {
    override fun id() = EntId.long()

    val user by belongsTo<Person>("user")
    val friend by belongsTo<Person>("friend")

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

The property references passed to `throughEntity(...)` (or
`throughLink(...)`) disambiguate which junction edge is the source
and which is the target — `sourceEdge` first, then `targetEdge`.

### M2M Bidirectional

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.long()

    val groups by manyToMany<Group>("groups")
        .throughEntity<Membership>(Membership::user, Membership::group)
}

class Group : EntSchema("groups", clientName = "groups") {
    override fun id() = EntId.long()

    val users by manyToMany<User>("users")
        .throughEntity<Membership>(Membership::group, Membership::user)
}

class Membership : EntSchema("memberships", clientName = "memberships") {
    override fun id() = EntId.long()

    val user by belongsTo<User>("user")
    val group by belongsTo<Group>("group")
}
```

Result:

- `User` can traverse to `groups` via its own `manyToMany<Group>("groups")` declaration
- `Group` can traverse to `users` via its own `manyToMany<User>("users")` declaration
- the database contains the `users`, `groups`, and `memberships` tables
- bidirectional traversal **requires** both endpoints to declare their own
  `manyToMany`; declaring one side does not add an edge to the other.
  Declaring both is the explicit API pattern —
  the orientation keys must pair-swap (one side passes
  `(user, group)`, the other passes `(group, user)`).

## Resulting Database Schema

Each `EntSchema` maps to one database table.

Column generation rules:

- `id()` defines the primary key column type and strategy
- declared fields become columns in declaration order
- included mixin fields are inserted where `include(...)` appears
- `belongsTo(...)` adds a foreign key column unless `.field(handle)` reuses an
  existing declared field
- `hasMany(...)`, `hasOne(...)`, and `manyToMany(...)` do not add local columns
  by themselves

Constraint and index rules:

- `belongsTo(...)` is required-by-default; `.nullable()` opts in to a nullable FK
- `.unique()` on `belongsTo(...)` adds a `UNIQUE` constraint on the FK column
- field-level `.unique()` becomes a single-column unique constraint
- `index("...", ...)` becomes a named secondary index
- `index(...).where(...)` becomes a partial index when the driver supports it

## Indexes

Indexes are declared as property declarations using field handles.
For synthesized FK columns, use `.fk` on the edge declaration:

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.int()

    val name by string("name")
    val email by string("email")
    val status by string("status")
    val priority by int("priority")

    val byNameEmail = index("idx_name_email", name, email).unique()
    val byStatus = index("idx_status_priority", status, priority)
}

// FK index using .fk on a belongsTo edge
class Friendship : EntSchema("friendships", clientName = "friendships") {
    override fun id() = EntId.int()

    val requester by belongsTo<User>("requester")
    val recipient by belongsTo<User>("recipient")

    val idx = index("idx_requester_recipient", requester.fk, recipient.fk).unique()
}
```

Single-column unique constraints are simpler -- just use `.unique()` on the
field directly. The index name is the first argument and is required.

Declared indexes also generate type-safe, index-friendly read helpers under
the repo's `indexes` namespace
(`client.users.indexes.email(...).find()`) -- see
[Queries -> Indexed Query Helpers](04-queries.md#indexed-query-helpers).

### Partial indexes

Partial (conditional) indexes include only rows matching a `WHERE` predicate:

```kotlin
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.int()

    val email by string("email")
    val active by bool("active").default(true)

    val activeEmail = index("idx_active_email", email).unique().where("active = true")
}
```

This generates `CREATE UNIQUE INDEX ... ON users (email) WHERE active = true`.
Partial indexes are useful for enforcing uniqueness on a subset of rows or
speeding up queries that always filter by a condition.

**Predicate normalization:** PostgreSQL's catalog deparses predicates
differently from the user-written form — adding outer parentheses,
decorating literals (`'a'::text`, `(1)::double precision`,
`'-5'::integer`), and promoting columns (`(v)::text` on a varchar,
`(x)::numeric` on an integer met by a decimal literal). The migration
differ normalizes both sides before comparing, so `status = 'active'`
and `((status)::text = 'active'::text)` are treated as equivalent.
Casts that change which rows the predicate selects — `score::integer`
on a float column, `c::text` on a citext column, `::char`, or any
length-modified cast — are part of the index's identity: removing or
adding one is real drift and surfaces as a drop + recreate. Predicates
the normalizer can't reconcile (casts on function results, arithmetic
grouping parens) should be declared exactly as `pg_get_expr` reports
them.

## Native Column Types (Postgres pgvector)

Some column types are specific to one database and are deliberately kept off the
portable base DSL. Postgres [`pgvector`](https://github.com/pgvector/pgvector) is
the first: `import entkt.postgres.vector.*` to declare `vector(n)` columns and
their indexes, so a Postgres-native field looks Postgres-native at the call site
(a schema that never imports it never sees these builders).

```kotlin
import entkt.postgres.vector.*   // postgresVector, postgresVectorIndex, VectorMetric

class Article : EntSchema("articles", clientName = "articles") {
    override fun id() = EntId.long()

    val title by text("title")

    // A vector(1536) column, generated as a `PgVector` property.
    val embedding by postgresVector("embedding", dimensions = 1536).nullable()

    // Vector indexes spell out the access method + metric (they are not btree).
    val embeddingHnsw = postgresVectorIndex("idx_articles_embedding_hnsw", embedding)
        .hnsw(VectorMetric.Cosine)
}
```

- `dimensions` must be `1..16000` (the `vector` column cap). HNSW/IVFFlat indexes
  additionally require `<= 2000`, enforced at `postgresVectorIndex(...)`.
- The generated property type is `PgVector` (from `entkt.postgres.vector`), a
  content-equal wrapper over `FloatArray`; build one with `PgVector.of(floats)`.
- `.nullable()` and `.comment(...)` apply; `.unique()` is rejected at build time
  (a UNIQUE index over a vector is not meaningful).
- Index metrics are `VectorMetric.Cosine` / `L2` / `InnerProduct`. Use
  `.hnsw(metric)` or `.ivfflat(metric, lists = N)` (with `lists > 0`). Two vector
  indexes on the same column are allowed when they differ by access method or
  metric.

**Dimension validation.** A `PgVector` carries no fixed dimension; the column's
declared `dimensions` is the source of truth. Generated saves, query predicates
(`embedding eq v`), and distance ordering reject a wrong-size vector with a
field-named error. `PgVector.of(...)` rejects non-finite components
(`NaN`/`Infinity`).

**Migrations / DDL.** A vector schema emits `CREATE EXTENSION IF NOT EXISTS
"vector"` (ordered before the table), a `vector(n)` column, and
`USING hnsw (col vector_cosine_ops)` / `USING ivfflat (...) WITH (lists = N)`
index DDL. A dimension change (`vector(1536)` -> `vector(3072)`) is classified
manual/destructive. The Flyway shadow workflow defaults to a pgvector-capable
image so these apply in the shadow database.

**Driver support.** A non-Postgres driver rejects a vector schema at `register()`
with `UnsupportedDriverCapabilityException` rather than failing later.

See [Queries -> Vector distance ordering](04-queries.md#vector-distance-ordering-pgvector)
for nearest-neighbor search.

## Typed JSON Fields (Postgres jsonb)

A `json(...)` field exposes a typed Kotlin value at the generated API
boundary while storing it as Postgres `jsonb` (`@Serializable` with the
default kotlinx mapper — see **JSON mapper** below):

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class PetMetadata(val nickname: String?, val tags: List<String>)

@Serializable
data class HighlightRect(val page: Int, val x: Double, val y: Double)

class Pet : EntSchema("pets", clientName = "pets") {
    override fun id() = EntId.long()

    val name by string("name")
    val metadata by json("pet_metadata", PetMetadata::class).nullable()
    // Reified form: json<PetMetadata>("pet_metadata")

    // Generic shapes work directly — the full type is captured, so the
    // generated property is List<HighlightRect> and elements round-trip
    // through HighlightRect's serializer (no wrapper class needed):
    val rects by json<List<HighlightRect>>("rects").nullable()
}
```

**JSON mapper.** kotlinx.serialization is the default and the zero-config
path: apply the Kotlin serialization compiler plugin
(`org.jetbrains.kotlin.plugin.serialization`), have
`kotlinx-serialization-json` available, and annotate json classes
`@Serializable`.

Jackson projects can switch mappers instead of adopting kotlinx by opting in
in both Gradle and application configuration:

```kotlin
// build.gradle.kts
entkt { jsonMapper.set("jackson") }

// Application setup (from io.entkt:jackson)
PostgresDriver(dataSource, jsonCodec = JacksonJsonCodec(objectMapper))
```

Use the same mapper in the Gradle configuration and `PostgresDriver`.
A mismatch fails during application startup. With Jackson, register
`jackson-module-kotlin` for Kotlin data classes; the configured
`ObjectMapper` controls serialization behavior.

- The generated property type is the supplied type (`metadata: PetMetadata?`,
  `rects: List<HighlightRect>?`). With the default kotlinx mapper, a type
  without serialization support fails at compile time.
- **Generic types** must use the reified form — a `KClass` cannot carry type
  arguments, so `json("rects", List::class)` is rejected at schema construction
  with a pointer to `json<List<HighlightRect>>("rects")`. Star projections
  (`List<*>`), `in`/`out` projections, and unresolved type parameters are also
  rejected there. Type arguments may be nullable (`List<HighlightRect?>`).
- With the kotlinx mapper, every class in the type needs a kotlinx serializer: `@Serializable` classes
  (including generic ones), primitives and `String`, and supported collection
  and array shapes. Built-in Kotlin arrays use their matching kotlinx serializer
  factories; generated source contains any opt-ins required for reference or
  unsigned arrays. An **enum used inside a JSON type must itself be
  `@Serializable`**.
- Configure kotlinx behavior with
  `PostgresDriver(dataSource, jsonCodec = KotlinxJsonCodec(Json { ignoreUnknownKeys = true }))`
  (the default is `KotlinxJsonCodec(Json.Default)`).
- `.nullable()` and `.comment()` apply; **defaults, `.unique()`, primary keys, and
  JSON indexes are rejected** with a clear error. Database-specific JSON indexes
  can be added via manual migrations.
- The generated column ref is a narrow `JsonColumn` -- it exposes **null checks
  only** (`Pet.metadata.isNull()` / `.isNotNull()` on nullable columns). Equality,
  membership, ordering, containment, and path predicates are out of scope in V1.

**Errors.** Encode and decode failures identify the table, column, and expected
type and retain the original serialization exception as their cause. Generated
repositories provide compile-time types for JSON fields.

**Migrations.** A JSON column renders `jsonb`. Migrations diff only database
facts (column existence, `jsonb` type, nullability) -- changing the Kotlin class,
property names, `@SerialName`s, or serializer config produces **no** automatic
migration, since the database can't reconstruct them.

**Driver support.** A non-Postgres driver rejects a typed JSON schema at
`register()` with `UnsupportedDriverCapabilityException`.

## Reusable Mixins

Reusable local field/index bundles can be shared via `EntMixin` and
`include(...)`:

```kotlin
class Timestamps(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt by time("created_at").immutable()
    val updatedAt by time("updated_at")
}

class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val timestamps = include(::Timestamps)
    val name by string("name")
    // User includes createdAt and updatedAt
}
```

Mixin fields are included in the generated entity class, create builder,
and update builder. Immutable fields (like `createdAt` above) are omitted
from the update builder. Relationship edges stay on the host schema.
