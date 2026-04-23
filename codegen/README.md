# :codegen

KotlinPoet-based generator: entity classes, create/update/query builders,
repos, `EntClient`.

## Generated output

For each schema the generator emits:

- **Entity data class** with typed properties, companion-object column refs
  (`User.name: StringColumn`, `User.age: NullableComparableColumn<Int>`), baked-in
  `EntitySchema` metadata, a `fromRow()` row decoder, and a nested `Edges`
  data class for eagerly loaded relationships.
- **`{Entity}Mutation` interface** — shared interface implemented by both
  Create and Update builders, with `var` properties for all mutable fields.
  Enables shared validators via `onBeforeSave`.
- **`{Entity}Create` builder** — DSL setters + `.save()`.
  Mints client UUIDs when `IdStrategy.CLIENT_UUID`. Implements `{Entity}Mutation`.
- **`{Entity}Update` builder** — DSL setters (immutable fields are elided) + `.save()`.
  Implements `{Entity}Mutation`. Exposes `entity` for hooks to inspect current state.
- **`{Entity}Query` builder** — `.where(...)`, `.orderBy(...)`, `.limit(...)`,
  `.offset(...)`, `.all()`, `.firstOrNull()`, edge traversal methods
  (e.g. `.queryPosts()`), and eager loading methods (e.g. `.withPosts { }`).
- **`{Entity}Repo`** — `.create { }`, `.update(entity) { }`, `.query { }`,
  `.byId(id)`, `.delete(entity)`, `.deleteById(id)`,
  `.createMany(vararg blocks)`, `.deleteMany(vararg predicates)`.
  Registers the entity's `EntitySchema` with the driver on construction.
- **`EntClient`** — single entry point holding one repo per entity, constructed
  with a `Driver` and an optional configuration lambda for lifecycle hooks.
- **Hooks DSL classes** — `EntClientConfig`, `EntClientHooks`, and per-entity
  `{Entity}Hooks` classes that provide a structured DSL for registering
  lifecycle hooks at client construction time.

## Lifecycle hooks

Hooks are registered once at client construction time via a structured DSL and
automatically inherited by transactional clients. They receive the actual
generated entity/builder types — not raw maps.

| Hook | Signature | When |
|------|-----------|------|
| `beforeSave` | `(UserMutation) -> Unit` | Both create & update, before validation |
| `beforeCreate` | `(UserCreate) -> Unit` | Create only, after beforeSave |
| `afterCreate` | `(User) -> Unit` | After successful insert |
| `beforeUpdate` | `(UserUpdate) -> Unit` | Update only, after beforeSave |
| `afterUpdate` | `(User) -> Unit` | After successful update |
| `beforeDelete` | `(User) -> Unit` | Before driver delete |
| `afterDelete` | `(User) -> Unit` | After successful delete |

```kotlin
val client = EntClient(driver) {
    hooks {
        users {
            beforeSave { it.updatedAt = Instant.now() }
            beforeCreate { it.createdAt = Instant.now() }
            beforeUpdate { update ->
                if (update.name != update.entity.name) println("name changed!")
            }
            afterCreate { user -> println("Created: ${user.name}") }
            beforeDelete { user -> println("Deleting: ${user.name}") }
        }
        posts {
            beforeSave { it.updatedAt = Instant.now() }
        }
    }
}
```

`beforeSave` accepts the shared `{Entity}Mutation` interface so the same
hook works for both creates and updates. Hooks are declared once and
automatically apply within transactions — no re-registration needed.

**Bulk operations run hooks.** `createMany` delegates to `create { }.save()`
per entry, and `deleteMany` queries then deletes through `delete(entity)` —
all lifecycle hooks fire for every row.

## Eager loading

Query builders support eager loading of related entities via `with{Edge}()`
methods. This avoids N+1 queries by batch-loading edges using `IN` predicates
after the main query.

```kotlin
val users = client.users.query {
    where(User.active eq true)
    withPosts {                          // load posts for each user
        where(Post.published eq true)    // optional: filter/order the edge
        orderBy(Post.createdAt.desc())
    }
}.all()

users[0].edges.posts  // → List<Post> (loaded)
users[0].edges.posts  // → null if withPosts() wasn't called
```

Each entity with edges gets a nested `Edges` data class with nullable
properties — `null` means not loaded, `emptyList()` means loaded but empty.

Supports all edge types (to-one, to-many, M2M via junction table) and
nested eager loading:

```kotlin
val owners = client.owners.query {
    withPets {
        withOwner()  // nested: also load each pet's owner
    }
}.all()
```
