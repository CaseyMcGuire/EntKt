# :codegen

KotlinPoet-based generator for schema-specific entities, drafts, queries,
repositories, lifecycle types, and clients.

## Generated output

Generated declarations fall into three groups: the application-facing entity
family, one schema-set-level client family, and internal adapters that connect
those types to the runtime. The tables below use a `User` schema as the
example; `{Entity}` is replaced by each schema class name.

### Per-entity application types

| Generated artifact | Purpose |
|---|---|
| `User.kt` / `User` | Immutable entity data class. Its companion exposes typed query columns and schema metadata, and its nested `Edges` class stores eager-loaded relationships as `EdgeState` values. |
| `UserCreateDraft.kt` / `UserCreateDraft` | Mutable, potentially incomplete create input. It tracks which fields were assigned. Application code receives it as the `create { ... }` or `CreateMutation.configure { ... }` DSL receiver rather than constructing it directly. |
| `UserUpdateDraft.kt` / `UserUpdateDraft` | Mutable update patch. It records assignments, unsets, and relationship changes without executing them. Application code receives it through `update(id) { ... }` or `UpdateMutation.configure { ... }`. |
| `UserMutation.kt` / `UserMutation` | Mutable field interface received by `beforeSave`. `UserCreateMutationView` and `UserUpdateMutationView` are the operation-specific mutation views exposed by hook contexts. These are hook contracts, not executable mutation objects. |
| `UserQuery.kt` / `UserQuery` | Typed query DSL for predicates, ordering, pagination, traversal, and edge selection. `all(viewerContext)` and `firstOrNull(viewerContext)` execute the captured query. |
| `UserRepo.kt` / `UserRepo` | Entity entry points exposed as `client.users`: `create`, `update`, `query`, `findById`, deletes, and supported bulk operations. `create` and `update` return runtime mutation operations; delete methods execute immediately. |
| `UserIndexes.kt` / `UserIndexes` | Generated only when the schema has an eligible index. Exact indexes expose `find(viewerContext)` and `query { ... }`; range-capable indexes also expose a range DSL. Access them through `client.users.indexes`. |
| `UserPrivacy.kt` | Typed privacy aliases, operation items, `UserWriteCandidate`, `UserUpdatePatch`, edge-change views, hook contexts, and the privacy/policy configuration scopes. See [Privacy](../docs/06-privacy.md) and [Hooks](../docs/05-hooks.md). |
| `UserValidation.kt` | Typed validation aliases, operation items, and validation configuration scopes. Validation reuses `UserWriteCandidate` from the privacy/lifecycle model. See [Validation](../docs/07-validation.md). |

`UserCreateDraft` does not implement `UserMutation`. Create hooks receive
generated mutation-view adapters so lifecycle code sees only the intended hook
contract. `UserUpdateDraft` implements `UserMutation`, but persistence still
happens only through the `UpdateMutation` returned by the repository.

`CreateMutation<Draft, Entity>` and `UpdateMutation<Draft, Entity>` are runtime
classes, not generated classes. They own the single-use `configure`, `save`,
and `saveAndLoad` operation lifecycle while generated repositories supply the
schema-specific draft and persistence adapter:

```kotlin
val creation: CreateMutation<UserCreateDraft, User> =
    client.users.create { name = "Ada" }

creation.configure { email = "ada@example.com" }
val user = creation.saveAndLoad(viewerContext).getOrThrow()
```

### Schema-set client types

These types are generated once for the complete schema set:

| Generated type | Purpose |
|---|---|
| `EntClient` | Long-lived, contextless root client containing one repository per schema. Every entity-operation terminal receives an operation-scoped `ViewerContext`. |
| `EntClientScope` | Common repository-only interface implemented by root, transaction, and hook client scopes. Accept this type when shared code needs repositories but must not start transactions. |
| `EntTransactionClient` | Transaction-bound implementation of `EntClientScope` supplied to `withTransaction`. It deliberately has no nested `withTransaction` method. |
| `ReadOnlyEntClient` | Read-only client available to privacy and validation rules. The same generated file contains one `{Entity}ReadRepo` per schema. Each read terminal still requires an explicit `ViewerContext`. |
| `EntClientConfig` | Constructor DSL receiver for transaction requirements, update and relationship-locking defaults, hooks, policies, and interceptors. |
| `EntClientHooks` | Schema-typed `hooks { users { ... } }` registration DSL backed by the runtime `EntityHooks` holder. No per-entity hook-holder class is generated. |
| `EntClientPolicies` | Schema-typed `policies { users(UserPolicy) }` registration DSL. |
| `EntClientInterceptors` | Schema-typed entity and global read-interceptor registration DSL. |

`EntReadRuntime`, the per-entity `{Entity}ReadSurface` interfaces,
`ResolvedEntClientConfig`, `ResolvedEntClientHooks`,
`ResolvedEntClientPolicies`, `ReadOnlyEntClientImpl`, and
`_EntHookClientScope` are generated framework wiring. They may need public or
package-visible Kotlin declarations so generated files can compose, but
application code should not construct or implement them. The same applies to
internal mappings, mutation specs, and adapters declared inside per-entity
files. Treat `@EntktInternal` as an explicit boundary rather than an invitation
to opt in.

### Optional Ent Viewer types

When Ent Viewer generation is enabled, codegen additionally emits one
`{Entity}ViewerEntity` adapter per schema and a
`GeneratedEntViewerRegistry`. Applications mount the registry; the adapters
are framework integration details. See [Ent Viewer](../docs/11-ent-viewer.md).

### Runtime types used by the generated API

The generated surface also refers to ordinary runtime types including
`CreateMutation`, `UpdateMutation`, `ReadResult`, `MutationResult`,
`ViewerContext`, `EntityHooks`, and the privacy/validation rule interfaces.
They are shared implementations and are therefore not regenerated per schema.

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
