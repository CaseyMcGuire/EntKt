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
  Enables shared `beforeSave` hooks registered through the client lifecycle
  DSL.
- **`{Entity}Create` builder** — DSL setters +
  `.save(viewerContext): MutationResult<Unit>` and
  `.saveAndLoad(viewerContext): MutationResult<Entity>`.
  Mints client UUIDs when `IdStrategy.CLIENT_UUID`. Implements `{Entity}Mutation`.
- **`{Entity}Update` builder** — DSL setters (immutable fields are elided) plus
  `.save(viewerContext): MutationResult<Unit>` /
  `.saveAndLoad(viewerContext): MutationResult<Entity>`.
  Implements `{Entity}Mutation`. The current owner row is loaded internally at
  the start of the save pipeline (bypassing LOAD privacy); hooks receive a
  `{Entity}UpdateHookContext` with `before`, `patch`, and a restricted
  `mutation` view.
- **`{Entity}Query` builder** — `.where(...)`, `.orderBy(...)`, `.limit(...)`,
  `.offset(...)`, `.all(viewerContext): ReadResult<List<E>>`,
  `.firstOrNull(viewerContext): ReadResult<E?>`, edge traversal methods
  (e.g. `.queryPosts()`), and edge loading methods (e.g. `.loadPosts { }`,
  returning an `EdgeLoad` handle whose `filterVisible()` opts that edge out
  of strict eager privacy).
- **`{Entity}Repo`** — `.create { }`, `.update(id) { }`, `.query { }`,
  `.findById(viewerContext, id): ReadResult<Entity?>`,
  `.delete(viewerContext, entity)`, `.deleteById(viewerContext, id)`,
  `.deleteMany(viewerContext, vararg predicates)`, and, for generated-ID repositories,
  `.createMany(viewerContext, vararg blocks)` — the mutation terminals return
  `MutationResult`. Explicit-ID repositories use `.create(id) { }` and do not
  currently expose a bulk-create signature.
  There is no generated lifecycle-aware `updateMany()` terminal.
  Registers the entity's `EntitySchema` with the driver on construction.
- **`EntClient`** — long-lived, contextless entry point holding one repo per
  entity, constructed with a `DatabaseDriver` and an optional
  lifecycle-configuration lambda. Every execution terminal receives its
  operation-scoped `ViewerContext` explicitly.
- **Hooks DSL classes** — `EntClientConfig`, `EntClientHooks`, and per-entity
  `{Entity}Hooks` classes that provide a structured DSL for registering
  lifecycle hooks at client construction time.
- **Lifecycle rule types** — generated scalar and batch aliases such as
  `UserLoadPrivacyRule` / `UserLoadBatchPrivacyRule` and
  `UserCreateValidationRule` / `UserCreateBatchValidationRule`. Both forms
  register under the existing operation names.

## Lifecycle hooks

Hooks are registered once at client construction time via a structured DSL and
automatically inherited by transactional clients. They receive the actual
generated entity/builder types — not raw maps.

| Hook | Signature | When |
|------|-----------|------|
| `beforeSave` | `(UserMutation) -> Unit` | Both create & update, before validation |
| `beforeCreate` | `(UserCreateHookContext) -> Unit` | Create only, after beforeSave |
| `afterCreate` | `(User) -> Unit` | After successful insert |
| `beforeUpdate` | `(UserUpdateHookContext) -> Unit` | Update only, after beforeSave |
| `afterUpdate` | `(User) -> Unit` | After successful update |
| `beforeDelete` | `(User) -> Unit` | Before driver delete |
| `afterDelete` | `(User) -> Unit` | After successful delete |

```kotlin
val client = EntClient(driver) {
    hooks {
        users {
            beforeSave { it.updatedAt = Instant.now() }
            beforeCreate { it.mutation.createdAt = Instant.now() }
            beforeUpdate { ctx ->
                println("Updating ${ctx.before.name}")
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

Scalar privacy rules, validators, and hooks automatically implement their
runtime batch contract by visiting values in encounter order. Explicit batch
callbacks use `batchPrivacyRule`, `batchValidationRule`, and `batchHook`; the
generated DSL registers them under the same `load` / `create` / `beforeCreate`
names in Kotlin, not parallel `*Batch` methods. Their generated JVM names use
`*BatchRule` / `*BatchHook` suffixes so Java lambda overload resolution remains
unambiguous. Privacy and validation evaluate rule-major, and hooks evaluate
hook-major. Privacy and validation callbacks receive phase-wide state
separately from an immutable `RuleBatch` of item-only generated values. Scalar
callbacks use `{ context, item -> ... }`; batch callbacks use
`{ context, batch -> ... }`. The shared context holds the captured read client
and, for privacy, the viewer context, while each item holds only
entity/candidate/patch state. Batch rules return read-only decisions through
`batch.decideEach { ... }` or
`batch.decideEachIndexed { index, item -> ... }`; this binds the result to its
originating batch and removes the free positional-list return. Application
code remains responsible for computing the right decision for each supplied
item.
Hooks keep their `List` input because they return `Unit` and have no result to
correlate.

**Bulk operations are phase-major and transactional.** `createMany` completes
all before hooks, preparation, CREATE privacy, and validation before one
logical `DatabaseDriver.insertMany`; it then hydrates every row, runs `afterCreate`, and
batch-evaluates returned LOAD privacy. `deleteMany` selects candidates once,
completes DELETE privacy, validation, and `beforeDelete`, then calls
`DatabaseDriver.deleteManyByIds` with the approved IDs and frozen effective predicates;
`afterDelete` sees only rows actually removed. The whole database operation
uses the caller's transaction or an EntKt-owned one. Postgres implements both
logical writes with set-based `INSERT` / `DELETE ... RETURNING` statements
when input/result correlation permits, with driver-managed fallback or
chunking kept inside the same transaction.

The driver SPI now requires `registeredIdColumn(table)`. Custom drivers can
inherit `deleteManyByIds`' correctness fallback (one predicate-based delete per
distinct ID) or override it with a set-based returning delete. Drivers that
support typed JSON must also override `copyJsonValue`; decorating and
transaction-scoped drivers forward that operation.

`createMany`'s returned LOAD phase keeps the mutation result contract: a
failure after an EntKt-owned insert is `Committed` only after commit is
confirmed; a confirmed rollback is `NotPersisted`, and an uncertain boundary
is `PersistenceUnknown`. The same failure in a caller-owned transaction is
`TransactionPending` and marks that transaction rollback-only.

## Edge loading

Query builders select related entities for loading via `load{Edge}()`
methods. The current executor avoids N+1 queries by batch-loading edges
using `IN` predicates after the main query.

LOAD privacy is batch-aware too: the first rule receives the ordered root
result, and later rules receive only the ordered still-unresolved subset.
Eager queries apply the same active-subset evaluation to their ordered,
deduplicated in-window targets. The terminal's exact supplied `ViewerContext`
instance is shared across root and eager evaluation.

```kotlin
val users = client.users.query {
    where(User.active eq true)
    loadPosts {                          // load posts for each user
        where(Post.published eq true)    // optional: filter/order the edge
        orderBy(Post.createdAt.desc())
    }
}.all(viewerContext).getOrThrow()

users[0].edges.posts                  // → EdgeState.Loaded(List<Post>)
users[0].edges.posts.requireLoaded()  // → List<Post>; throws EdgeNotLoadedException if loadPosts() wasn't called
```

Each entity with edges gets a nested `Edges` data class whose properties
are `EdgeState` values defaulting to `EdgeState.Unloaded` — to-many edges
are `EdgeState<List<Target>>` (`Loaded(emptyList())` means loaded but
empty), to-one edges are `EdgeState<Target?>` (`Loaded(null)` means
loaded with no returned target).

Supports all edge types (to-one, to-many, M2M via junction table) and
nested edge loading:

```kotlin
val owners = client.owners.query {
    loadPets {
        loadOwner()  // nested: also load each pet's owner
    }
}.all(viewerContext).getOrThrow()
```
