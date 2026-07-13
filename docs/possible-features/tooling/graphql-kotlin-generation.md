# RFC: GraphQL Package Generation

## Status

Possible future feature. This is not implemented.

## Summary

Generate optional GraphQL-facing types and resolver scaffolding for entkt
entities.

V1 is read-only and privacy-preserving. It should generate a shared
framework-neutral surface plus adapters for:

- Expedia GraphQL Kotlin (`graphql-kotlin-schema-generator` /
  `graphql-kotlin-spring-server`)
- Netflix DGS (`com.netflix.graphql.dgs`)

The Expedia adapter should follow GraphQL Kotlin's code-first model. The DGS
adapter should follow DGS's schema-first model by generating SDL plus resolver
classes.

## Motivation

entkt already knows a lot about the application model:

- entity types
- scalar field types
- nullability
- IDs
- edges
- generated query APIs
- privacy-aware load behavior

A GraphQL generator could use that metadata to reduce boilerplate in sample
projects and applications that expose entkt models through GraphQL.

The goal is to give users a useful starting point while still letting
applications own API shape beyond the generated default, authentication,
authorization, and error mapping.

## Non-Goals

- Do not add a hard GraphQL dependency to entkt runtime modules.
- Do not require GraphQL for normal entkt usage.
- Do not generate a complete production GraphQL server in the first version.
- Do not bypass entkt privacy rules.
- Do not implement field-level privacy as part of this feature.
- Do not generate mutations in the first version.
- Do not generate GraphQL subscriptions in the first version.
- Do not generate Relay connections or cursors in the first version.
- Do not implement request-scoped DataLoader batching in the first version.

## Proposed Shape

Add optional GraphQL generation to the existing entkt Gradle/codegen flow:

```kotlin
entkt {
    graphql {
        enabled.set(true)
        packageName.set("example.graphql")
        frameworks.set(setOf(GraphqlFramework.EXPEDIA, GraphqlFramework.DGS))
    }
}
```

GraphQL exposure should be declared on the schema, not in Gradle, so the API
shape lives beside the entity and field definitions:

```kotlin
class Session : EntSchema("sessions") {
    override fun id() = EntId.long()

    graphql {
        exclude()
    }
}

class User : EntSchema("users") {
    override fun id() = EntId.long()

    val name = string("name")
    val passwordHash = string("password_hash").graphql { exclude() }
    val resetToken = string("reset_token").graphql { exclude() }
}
```

Generated files could live under:

```text
build/generated/entkt/graphql
```

The generator would emit:

- GraphQL DTO types for entities
- root query resolver scaffolding
- edge resolver scaffolding
- mapper functions from entkt entities to GraphQL DTOs
- DGS SDL files when the DGS adapter is enabled

Example generated API:

```kotlin
class PostGraphqlQuery(
    private val client: EntClient,
) {
    fun post(id: Long): PostGraphql? =
        client.posts.visibleByIdOrNull(id)?.toGraphql()

    fun posts(limit: Int = 50, offset: Int = 0): List<PostGraphql> =
        client.posts.query {
            this.limit(limit)
            this.offset(offset)
        }.visibleAll().map { it.toGraphql() }
}
```

GraphQL Kotlin can discover this through normal code-first schema generation
using `TopLevelObject`. DGS can expose the same generated behavior through
generated `.graphqls` SDL and `@DgsComponent` resolver classes.

## Module Layout

GraphQL support should be optional and isolated from the core runtime:

```text
:graphql-core
:graphql-expedia
:graphql-dgs
```

`:graphql-core` contains shared contracts and helper types used by generated
GraphQL code. It may depend on `:runtime`, but `:runtime` must not depend on
GraphQL.

`:graphql-expedia` contains Expedia GraphQL Kotlin integration helpers and
compile-time dependencies appropriate for the Expedia adapter.

`:graphql-dgs` contains DGS integration helpers and compile-time dependencies
appropriate for the DGS adapter.

Applications opt into the adapter modules they use. Having a GraphQL module on
the classpath must not expose a GraphQL endpoint by itself.

## Generated DTOs

Generated GraphQL DTOs should be separate from entkt entity data classes.

Example:

```kotlin
data class PostGraphql(
    val id: Long,
    val title: String,
    val body: String?,
    val authorId: Long,
)
```

Reasons to avoid exposing entities directly:

- GraphQL field visibility may differ from database fields.
- Generated entities may include internal edge containers.
- Future field-level privacy would be easier to add at the DTO boundary.
- API naming can evolve separately from storage naming.

V1 exposes all non-sensitive scalar fields by default. Schema-declared
`.sensitive()` fields are omitted from the GraphQL schema entirely, not
redacted. Applications can exclude additional entities or fields from their
schema definitions.

## Resolver Scaffolding

The first version should generate conservative scaffolding, not a large
framework.

Generated query roots might include:

```kotlin
class UserGraphqlQuery(private val client: EntClient)
class PostGraphqlQuery(private val client: EntClient)
```

Generated DTOs can expose edge fields through resolver methods rather than
embedding loaded edge objects in the DTO:

```kotlin
class PostGraphqlEdges(
    private val client: EntClient,
) {
    fun author(post: PostGraphql): UserGraphql? =
        client.users.visibleByIdOrNull(post.authorId)?.toGraphql()
}
```

The exact generated class shape may differ per adapter:

- Expedia GraphQL Kotlin should generate code-first query and field resolver
  classes.
- DGS should generate SDL plus annotated DGS component classes.

Mutations are deferred. When they are added, they should be opt-in and covered
by a separate design pass for write privacy, validation errors, and conflict
mapping.

## Privacy Behavior

Generated resolvers must use public entkt APIs so privacy remains enforced:

- direct reads use visible by-id semantics
- list reads use privacy-aware query terminals
- raw counts and raw existence checks are not generated by default
- edge resolvers use generated query/repo APIs

Generated GraphQL code should not use driver-level APIs.

By-id reads should return `null` when the row is missing or not visible to the
current privacy context. This matches GraphQL's nullable field model and avoids
existence disclosure by default.

List reads should omit rows denied by load privacy. The generator should use
the existing generated query API, so read interceptors, soft-delete behavior,
JSON decoding, native types, and load privacy remain in one path.

GraphQL error mapping should remain application-owned in the first version.
For example, applications can decide whether `PrivacyDeniedException` becomes:

- a GraphQL error
- `null`
- a domain-specific error object

## Edge Fields

V1 should generate edge resolver scaffolding for declared edges:

- `belongsTo` and `hasOne` return nullable DTOs.
- `hasMany` and many-to-many return lists of DTOs.
- Resolvers call normal ent query APIs and honor privacy.

FK scalar fields may still be exposed when they are non-sensitive scalar
columns, but they are not a substitute for GraphQL edge resolvers.

V1 does not promise batching. Edge resolvers may issue separate ent queries.
Request-scoped DataLoader support is an important follow-up so nested GraphQL
queries do not become N+1 under common query shapes.

## Pagination

The first version should support simple offset pagination only:

```kotlin
fun posts(limit: Int = 50, offset: Int = 0): List<PostGraphql>
```

Cursor pagination should be a future feature because it needs a stable
ordering contract and careful interaction with privacy.

## Configuration

Gradle configuration should only control generation and adapter selection:

```kotlin
entkt {
    graphql {
        enabled.set(true)
        packageName.set("example.graphql")
        frameworks.set(setOf(GraphqlFramework.EXPEDIA, GraphqlFramework.DGS))
    }
}
```

GraphQL exposure belongs in the schema, similar to EntGo's pattern of attaching
GraphQL behavior to schema definitions rather than central build config:

```kotlin
class Session : EntSchema("sessions") {
    override fun id() = EntId.long()

    graphql {
        exclude()
    }
}

class User : EntSchema("users") {
    override fun id() = EntId.long()

    val name = string("name")
    val passwordHash = string("password_hash").graphql { exclude() }
    val resetToken = string("reset_token").graphql { exclude() }
}
```

The default exposure model is exclusion-based:

- GraphQL generation is off unless `graphql.enabled` is true.
- All generated entities are included unless the schema excludes them.
- All non-sensitive scalar fields are included unless the field excludes them.
- Sensitive fields are always omitted.

V1 should not include a broad field include-list API. If a project wants a
fully curated public GraphQL schema, it can use generated DTOs/resolver
scaffolding as a starting point and own the API layer manually.

## Dependency Strategy

GraphQL support should be optional.

The recommended approach is a separate optional module family with generated
source enabled from the Gradle plugin. No GraphQL dependency should be added to
`:runtime`, `:schema`, or normal generated ent code.

## Relationship To Expedia GraphQL Kotlin

GraphQL Kotlin is a code-first library built on top of `graphql-java`. Its
schema generator reflects over Kotlin query, mutation, and subscription objects
passed as `TopLevelObject`s.

entkt GraphQL generation should produce Kotlin types and resolver objects that
fit that model.

Example application setup:

```kotlin
val schema = toSchema(
    config = SchemaGeneratorConfig(
        supportedPackages = listOf("example.graphql"),
    ),
    queries = listOf(
        TopLevelObject(PostGraphqlQuery(client)),
        TopLevelObject(UserGraphqlQuery(client)),
    ),
)
```

Spring applications may instead use `graphql-kotlin-spring-server` integration
and register generated query classes as beans.

## Relationship To DGS

DGS is schema-first. The DGS adapter should generate GraphQL SDL and annotated
resolver classes.

Example generated SDL:

```graphql
type Post {
  id: ID!
  title: String!
  body: String
  authorId: ID!
  author: User
}

type Query {
  post(id: ID!): Post
  posts(limit: Int = 50, offset: Int = 0): [Post!]!
}
```

Example generated resolver shape:

```kotlin
@DgsComponent
class PostDgsResolver(
    private val client: EntClient,
) {
    @DgsQuery
    fun post(id: Long): PostGraphql? =
        client.posts.visibleByIdOrNull(id)?.toGraphql()
}
```

The DGS module should not create an endpoint or security policy by itself.
Applications still own DGS server setup, authentication, and GraphQL error
mapping.

## Performance Follow-Up

V1 edge resolvers are allowed to issue separate ent queries. That is simple and
preserves privacy because every resolver goes through generated repos.

A follow-up should add request-scoped batching/DataLoader support:

- batch by-id `belongsTo`/`hasOne` loads
- batch `hasMany` loads by parent id
- batch many-to-many loads through junction tables
- preserve privacy context per GraphQL request
- avoid raw driver access

## Open Questions

- Should raw IDs be exposed as `Long`/`UUID`, or should the generator support
  GraphQL `ID` wrappers?
- How should `PrivacyDeniedException` map to GraphQL responses?
- Should schema descriptions or annotations customize GraphQL names and field
  descriptions?
- Should V1 emit DTO data classes, wrapper classes, or different shapes per
  framework adapter?
- Should request-scoped DataLoader support be part of the first implementation
  milestone if edge resolvers are enabled by default?

## Test Requirements

Before implementation, add tests for:

- generated DTO field nullability matches entkt entity nullability
- generated query resolvers compile against GraphQL Kotlin
- generated DGS SDL and resolver classes compile
- generated resolvers use public entkt APIs, not driver APIs
- sensitive fields are omitted by default
- excluded entities and fields are not generated
- by-id reads return null for missing or privacy-denied rows
- list reads omit privacy-denied rows
- edge resolvers are generated for belongsTo, hasOne, hasMany, and many-to-many
- generated package names are configurable
- GraphQL Kotlin schema generation succeeds for a small sample schema
- DGS schema loading succeeds for a small sample schema
