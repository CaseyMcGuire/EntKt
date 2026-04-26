# Edges

Edges define relationships between entities. This page explains how each
edge type maps to database tables and columns, and what code is generated
for each.

For the schema DSL reference (modifiers, syntax), see [Schema](schema.md#edges).

## Edge types at a glance

| Relationship | Schema DSL | FK lives on | Table created |
|---|---|---|---|
| One-to-many | `hasMany<Post>("posts")` | Target table | None |
| Many-to-one | `belongsTo<User>("author").inverse(User::posts)` | This table | None |
| One-to-one | `hasOne<Profile>("profile")` / `belongsTo<User>("user").unique()` | BelongsTo side | None |
| Many-to-many | `manyToMany<Group>("groups").through<UserGroup>(...)` | Junction table | Junction table |

## One-to-many / many-to-one

A one-to-many relationship is always declared from both sides:

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()
    val posts = hasMany<Post>("posts")     // "one" side — no column added to users table
}

class Post : EntSchema("posts") {
    override fun id() = EntId.long()
    val author = belongsTo<User>("author")  // "many" side — adds author_id column to posts table
        .inverse(User::posts)               // links this edge to User.posts
        .required()                         // author_id is NOT NULL
}
```

### What this produces

**`posts` table:**

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | (per ID strategy) | PRIMARY KEY |
| `author_id` | (matches User's ID type) | NOT NULL, REFERENCES users(id) |
| ... | | |

The FK column name defaults to `{edge_name}_id` — so `belongsTo<User>("author")`
creates `author_id`. Override it with `.field()`:

```kotlin
class Post : EntSchema("posts") {
    override fun id() = EntId.long()
    val writerId = long("writer_id")
    val author = belongsTo<User>("author").inverse(User::posts).required().field(writerId)
}
```

When you use `.field(handle)`, the FK column reuses the declared field
handle rather than synthesizing a new column.

### Generated code

The `belongsTo(...)` edge synthesizes a typed FK property on the entity
and its builders:

```kotlin
// Generated Post entity
data class Post(
    val id: Long,
    val title: String,
    val authorId: UUID,      // ← synthesized from the edge
    // ...
)

// Create builder
client.posts.create {
    title = "Hello"
    authorId = alice.id      // set FK directly
    author = alice           // or set the entity — authorId is derived
}.save()
```

The `hasMany(...)` side generates a query traversal method and an eager-loading
method on the query builder:

```kotlin
// Query traversal: "find all posts for this user"
val posts = client.users.query { ... }.first().queryPosts().all()

// Eager loading: batch-load posts for all queried users
val users = client.users.query {
    withPosts { orderBy(Post.createdAt.desc()) }
}.all()
users[0].edges.posts  // → List<Post>
```

### Runtime metadata

The generated `EntitySchema` records the join shape so the driver can
resolve edge predicates and eager loads:

```
// On User: to-many side
edges = mapOf(
    "posts" to EdgeMetadata(
        targetTable = "posts",
        sourceColumn = "id",          // User.id
        targetColumn = "author_id",   // FK on posts
    ),
)

// On Post: to-one side
edges = mapOf(
    "author" to EdgeMetadata(
        targetTable = "users",
        sourceColumn = "author_id",   // FK on posts
        targetColumn = "id",          // User.id
    ),
)
```

## Many-to-many

M2M relationships use a junction table — a separate `EntSchema` with
`belongsTo()` edges pointing at both sides:

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()
    val groups = manyToMany<Group>("groups")
        .through<UserGroup>(UserGroup::user, UserGroup::group)
}

class Group : EntSchema("groups") {
    override fun id() = EntId.long()
    val name = string("name")
}

class UserGroup : EntSchema("user_groups") {
    override fun id() = EntId.long()
    val user = belongsTo<User>("user").required()
    val group = belongsTo<Group>("group").required()
}
```

### What this produces

**`user_groups` table:**

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | (per ID strategy) | PRIMARY KEY |
| `user_id` | (matches User's ID type) | NOT NULL, REFERENCES users(id) |
| `group_id` | (matches Group's ID type) | NOT NULL, REFERENCES groups(id) |

The junction table is a normal entity — you can add fields to it (e.g.
`role`, `joined_at`) and query it directly if needed.

### Runtime metadata

The generated `EntitySchema` carries junction info for M2M edges:

```
// On User
edges = mapOf(
    "groups" to EdgeMetadata(
        targetTable = "groups",
        sourceColumn = "id",
        targetColumn = "id",
        junctionTable = "user_groups",
        junctionSourceColumn = "user_id",
        junctionTargetColumn = "group_id",
    ),
)
```

The target side (`Group`) gets a reverse edge entry automatically so that
edge predicates and eager loading work from either direction.

### Self-referential M2M

When both sides of an M2M point to the same entity (e.g. friendships),
the junction has two edges to the same schema. Use `sourceEdge` and
`targetEdge` to tell entkt which junction edge corresponds to which side:

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

The typed property references (`Friendship::user`, `Friendship::friend`)
tell entkt which junction FK is the source and which is the target.

### Ambiguous junctions

`sourceEdge`/`targetEdge` also disambiguate when a junction has multiple
edges to the same target type for different purposes:

```kotlin
class Project : EntSchema("projects") {
    override fun id() = EntId.long()
    val assignees = manyToMany<Pet>("assignees")
        .through<ProjectAssignment>(ProjectAssignment::project, ProjectAssignment::assignee)
}

class ProjectAssignment : EntSchema("project_assignments") {
    override fun id() = EntId.long()
    val project = belongsTo<Project>("project").required()
    val assignee = belongsTo<Pet>("assignee").required()
    val reviewer = belongsTo<Pet>("reviewer")              // different role, same target type
}
```

## ON DELETE actions

FK columns carry a referential action that controls what happens when the
referenced row is deleted. See [Schema — ON DELETE Actions](schema.md#on-delete-actions)
for the DSL reference. The table mapping:

| Edge declaration | FK constraint |
|---|---|
| `belongsTo<User>("author")` (optional) | `REFERENCES users(id) ON DELETE SET NULL` |
| `belongsTo<User>("author").required()` | `REFERENCES users(id) ON DELETE RESTRICT` |
| `belongsTo<User>("owner").required().onDelete(CASCADE)` | `REFERENCES users(id) ON DELETE CASCADE` |

When no explicit `.onDelete()` is set, the default is inferred from
nullability: `SET NULL` for optional FKs, `RESTRICT` for required FKs.

Both `PostgresDriver` and `InMemoryDriver` enforce these actions at
runtime.

## Edge resolution internals

When building the runtime `EdgeMetadata`, codegen resolves each edge's
join columns:

1. **BelongsTo** (`belongsTo(...)`) — the FK sits on this entity's
   table. The column name is `{edge_name}_id` (or the `.field()` override).
   Join: `sourceColumn = fk_column, targetColumn = "id"`.

2. **HasMany / HasOne** (`hasMany(...)`, `hasOne(...)`) — the FK sits on the
   target table. Codegen finds the inverse `belongsTo` edge on the target
   via `.inverse()` and reads its FK column.
   Join: `sourceColumn = "id", targetColumn = fk_column`.

3. **ManyToMany** (`manyToMany(...).through(...)`) — both source and target
   join on `id`; the junction table provides the bridge columns. The target
   schema gets a synthetic reverse edge entry so predicates and eager loading
   work from the target side.
