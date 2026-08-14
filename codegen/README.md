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
- **`{Entity}Create` builder** — DSL setters + `.save(): MutationResult<Unit>`
  and `.saveAndLoad(): MutationResult<Entity>`.
  Mints client UUIDs when `IdStrategy.CLIENT_UUID`. Implements `{Entity}Mutation`.
- **`{Entity}Update` builder** — DSL setters (immutable fields are elided) plus
  `.save(): MutationResult<Unit>` / `.saveAndLoad(): MutationResult<Entity>`.
  Implements `{Entity}Mutation`. The current owner row is loaded internally at
  the start of the save pipeline (bypassing LOAD privacy); hooks receive a
  `{Entity}UpdateHookContext` with `before`, `patch`, and a restricted
  `mutation` view.
- **`{Entity}Query` builder** — `.where(...)`, `.orderBy(...)`, `.limit(...)`,
  `.offset(...)`, `.all(): ReadResult<List<E>>`,
  `.firstOrNull(): ReadResult<E?>`, edge traversal methods
  (e.g. `.queryPosts()`), and eager loading methods (e.g. `.withPosts { }`,
  returning an `EagerLoad` handle whose `filterVisible()` opts that edge out
  of strict eager privacy).
- **`{Entity}Repo`** — `.create { }`, `.update(id) { }`, `.query { }`,
  `.findById(id): ReadResult<Entity?>`, `.delete(entity)`, `.deleteById(id)`,
  `.createMany(vararg blocks)`, `.deleteMany(vararg predicates)` — the four
  mutation terminals return `MutationResult`.
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

**Bulk operations run hooks and are atomic.** `createMany` drives the same
per-row create pipeline, and `deleteMany` queries then deletes through the
shared per-row delete pipeline — all lifecycle hooks fire for every row, and
the whole batch shares one transaction (the caller's, or an EntKt-owned one).

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
}.all().getOrThrow()

users[0].edges.posts                  // → EdgeState.Loaded(List<Post>)
users[0].edges.posts.requireLoaded()  // → List<Post>; throws EdgeNotLoadedException if withPosts() wasn't called
```

Each entity with edges gets a nested `Edges` data class whose properties
are `EdgeState` values defaulting to `EdgeState.Unloaded` — to-many edges
are `EdgeState<List<Target>>` (`Loaded(emptyList())` means loaded but
empty), to-one edges are `EdgeState<Target?>` (`Loaded(null)` means
loaded with no returned target).

Supports all edge types (to-one, to-many, M2M via junction table) and
nested eager loading:

```kotlin
val owners = client.owners.query {
    withPets {
        withOwner()  // nested: also load each pet's owner
    }
}.all().getOrThrow()
```
