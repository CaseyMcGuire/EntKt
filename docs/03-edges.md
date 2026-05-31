# Edges

Edges define relationships between entities. This page explains how each
edge type maps to database tables and columns, and what code is generated
for each.

For the schema DSL reference (modifiers, syntax), see [Schema](02-schema.md#edges).

## Edge types at a glance

| Relationship | Schema DSL | FK lives on | Table created |
|---|---|---|---|
| One-to-many | `hasMany<Post>("posts")` | Target table | None |
| Many-to-one | `belongsTo<User>("author").inverse(User::posts)` | This table | None |
| One-to-one | `hasOne<Profile>("profile")` / `belongsTo<User>("user").unique()` | BelongsTo side | None |
| Many-to-many | `manyToMany<Group>("groups").throughEntity<UserGroup>(...)` *or* `.throughLink<UserGroup>(...)` | Junction table | Junction table |

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
                                            // required-by-default — author_id is NOT NULL
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
    val author = belongsTo<User>("author").inverse(User::posts).field(writerId)
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

// Create builder — FK assignment is the only public to-one write path.
client.posts.create {
    title = "Hello"
    authorId = alice.id
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
`belongsTo()` edges pointing at both sides. The schema author picks
the write model with one of two markers (see
[Schema → Many-to-Many](02-schema.md#many-to-many) for the full
comparison):

- `.throughEntity<Junction>(sourceEdge, targetEdge)` — junction is a
  domain entity, mutated through its generated repo
  (e.g. `client.userGroups.create { ... }.save()`).
- `.throughLink<Junction>(sourceEdge, targetEdge)` — junction is pure
  relationship storage. The generated update builder gets direct
  id-only mutators on the M2M edge (see
  [Link-table M2M mutators](#link-table-m2m-mutators) below).

```kotlin
class User : EntSchema("users") {
    override fun id() = EntId.long()
    val groups = manyToMany<Group>("groups")
        .throughEntity<UserGroup>(UserGroup::user, UserGroup::group)
}

class Group : EntSchema("groups") {
    override fun id() = EntId.long()
    val name = string("name")
}

class UserGroup : EntSchema("user_groups") {
    override fun id() = EntId.long()
    val user = belongsTo<User>("user")
    val group = belongsTo<Group>("group")
}
```

The two refs name the junction's `belongsTo` edges in source-first
order: `sourceEdge` (here `UserGroup::user`) targets the schema
declaring the `manyToMany` (`User`); `targetEdge` (here
`UserGroup::group`) targets the M2M target type (`Group`). Schema
finalization rejects the wrong orientation.

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

**No auto-synthesized reverse edge.** The target side (`Group`) does
*not* automatically get a reverse traversal edge. Bidirectional
traversal requires `Group` to declare its own `manyToMany<User>`
explicitly, with the orientation key pair-swapped (one side passes
`(user, group)`, the other passes `(group, user)`) so codegen
recognizes them as opposite sides of the same canonical relationship.
Adding a `manyToMany` on schema `X` never silently introduces methods
on schema `Y` — see
[Schema → M2M Bidirectional](02-schema.md#m2m-bidirectional). Forward
query traversal of a one-sided declaration still works (it lowers to
`Predicate.HasM2MEdgeFrom` against the source schema's own forward
edge metadata, no reverse-edge entry needed on the target).

### Link-table M2M mutators

`throughLink` edges expose direct id-only mutators on the generated
update builder. `throughEntity` edges do not — their writes go through
the junction repo.

```kotlin
client.withTransaction { tx ->
    tx.posts.update(post.id) {
        tags.add(tagA.id)        // stage a single new link
        tags.remove(oldTag.id)   // stage a delete
    }.save()
}
```

The full API on each helper-eligible edge:

| Method | Effect | Notes |
|---|---|---|
| `tags.add(id)` | Stage a new link from this owner to the target id | id is typed as the target's id scalar (e.g. `Long`, `UUID`); no entity-arg overload |
| `tags.remove(id)` | Stage a delete of the (owner, target) link | No-op at the database layer if no such link exists; intent surface still records the call |
| `tags.set(ids)` | Replace the entire link set with the given ids | Mutually exclusive with `add` / `remove` in the same mutation |

**Same-mutation rules.**
- `set(...)` and `add(...)` / `remove(...)` are mutually exclusive
  for a given edge in one save. Mixing them throws
  `IllegalStateException` fail-fast at the offending call site
  (the error message names the edge).
- Same-id mixed-direction is also rejected at the call site —
  `add(x); remove(x)` (or `remove(x); add(x)`) throws
  `IllegalStateException` at the second call. The two delta sets
  are disjoint by construction; no cancellation logic runs
  downstream.
- Duplicate ids in `add` / `remove` / `set` dedupe internally.

**Transaction and capability requirements.** A link-table M2M update
requires a transaction-scoped client and a driver that supports
either `readRowForUpdate` (true row lock) or
`serializeOwnerEdgeAndRead` (cooperative serialization).
`PostgresDriver` reports both as `true`. A missing transaction throws
`TransactionRequiredException`; a driver without either capability
throws `UnsupportedDriverCapabilityException`. Both preflights fire
before any owner-row read, hook, or driver write. See
[Drivers — Locking (RFC #4)](10-drivers.md#locking-rfc-4) for the
capability surface.

**Edge-only updates** (`update(id) { tags.add(x) }` with no scalar
fields touched) are still owner update operations — `beforeSave`,
`beforeUpdate`, privacy, validation, and `afterUpdate` all fire.
If scalar / FK fields stay empty after hooks and update defaults,
the owner-row `UPDATE` is skipped (no phantom write); only the
junction inserts and `DELETE`s emit. If an `updateDefault` like
`updatedAt = updateDefaultNow()` populates a scalar value, the
owner UPDATE fires alongside the junction writes.

**Returned entity state.** `save()` returns the owner entity with
scalar / FK fields reflecting the saved owner row. The M2M edge
itself is returned in the normal unloaded state — to inspect the
new link set, requery with eager loading (`with{Edge}`).

**Hook visibility.** Before hooks see the captured pending edge
ops via `ctx.pendingEdges.tags` (a read-only `PendingEdgeOps<ID>`).
Privacy and validation rules see the full intent + computed
database delta via `ctx.edgeChanges.tags` (an `EdgeChanges<ID>`
with `added` / `removed` deltas). See
[Hooks → The Update Hook Context](05-hooks.md#the-update-hook-context).

### Self-referential M2M

When both sides of an M2M point to the same entity (e.g. friendships),
the junction has two edges to the same schema. Use `sourceEdge` and
`targetEdge` to tell entkt which junction edge corresponds to which side:

```kotlin
class Person : EntSchema("people") {
    override fun id() = EntId.long()
    val friends = manyToMany<Person>("friends")
        .throughEntity<Friendship>(Friendship::user, Friendship::friend)
}

class Friendship : EntSchema("friendships") {
    override fun id() = EntId.long()
    val user = belongsTo<Person>("user")
    val friend = belongsTo<Person>("friend")
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
        .throughEntity<ProjectAssignment>(ProjectAssignment::project, ProjectAssignment::assignee)
}

class ProjectAssignment : EntSchema("project_assignments") {
    override fun id() = EntId.long()
    val project = belongsTo<Project>("project")
    val assignee = belongsTo<Pet>("assignee")
    val reviewer = belongsTo<Pet>("reviewer").nullable()   // different role, same target type
}
```

## ON DELETE actions

FK columns carry a referential action that controls what happens when the
referenced row is deleted. See [Schema — ON DELETE Actions](02-schema.md#on-delete-actions)
for the DSL reference. The table mapping:

| Edge declaration | FK constraint |
|---|---|
| `belongsTo<User>("author")` | `REFERENCES users(id) ON DELETE RESTRICT` |
| `belongsTo<User>("author").nullable()` | `REFERENCES users(id) ON DELETE SET NULL` |
| `belongsTo<User>("owner").onDelete(CASCADE)` | `REFERENCES users(id) ON DELETE CASCADE` |

`belongsTo(...)` is required by default; add `.nullable()` to declare an
nullable relationship. When no explicit `.onDelete()` is set, the default
is inferred from nullability: `SET NULL` for nullable FKs, `RESTRICT` for
required FKs.

`PostgresDriver` enforces these actions via `REFERENCES ... ON DELETE`
in the generated DDL.

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

3. **ManyToMany** (`manyToMany(...).throughEntity(...)` / `.throughLink(...)`) —
   both source and target join on `id`; the junction table provides the
   bridge columns. The runtime metadata for forward traversal lives only
   on the source schema (the one declaring the `manyToMany`); no reverse
   entry is synthesized on the target. Forward query traversal lowers
   to `Predicate.HasM2MEdgeFrom(sourceTable, edgeName, parent)` so the
   predicate runs against the source schema's own metadata. Bidirectional
   traversal requires both endpoints to declare their own pair-swapped
   `manyToMany`.
