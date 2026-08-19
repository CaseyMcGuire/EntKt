# RFC: Schema Declaration Names As Generated API

## Status

Implemented as of 2026-08-19. Accepted as a design direction 2026-08-18.

This RFC generalizes the declaration-name intent described by the implemented
[Field-Backed FK Declaration Names](../edge-mutation/06-field-backed-fk-declaration-names.md)
and
[To-One FK Mutation And Nullability](../edge-mutation/02-to-one-assignment-nullability.md)
RFCs. Those RFCs captured Kotlin declaration names for a narrow FK surface,
while the generator still derived ordinary field and edge APIs from storage
strings and client properties from an English pluralizer.

Where those documents describe storage-derived public names or fallback
behavior, this RFC supersedes that naming contract.

## Summary

EntKt must never singularize or pluralize a word to create a generated API.
Generated Kotlin names come from explicit Kotlin-facing declarations. Storage
names remain independent, explicit metadata.

```kotlin
class Person : EntSchema(
    tableName = "people",
    clientName = "people",
) {
    val displayName by string("display_name")
    val sentRequests by hasMany<Friendship>("sent_requests")
}
```

The declarations above produce names such as:

```kotlin
Person
Person.displayName
Person.sentRequests

client.people
person.displayName
person.edges.sentRequests

client.people.query {
    where(Person.displayName eq "Alice")
    querySentRequests { /* ... */ }
    loadSentRequests { /* ... */ }
}
```

The storage identifiers remain `people`, `display_name`, and `sent_requests`.
They do not determine the generated Kotlin identifiers.

The contract is:

| Generated surface | Authoritative source |
|---|---|
| Entity and entity-prefixed type names | Concrete schema class name |
| Client repository/configuration property | Required `clientName` |
| Scalar field API | Delegated Kotlin field property |
| Edge API | Delegated Kotlin edge property |
| Fixed ID API | Reserved framework name `id` |
| Tables, columns, indexes, constraints, edge lookup keys | Explicit storage strings |

No English dictionary, locale, suffix rule, target-type guess, or storage-name
normalization participates in those public names.

## Motivation

### A Kotlin-first schema needs Kotlin-first names

The current model commonly makes these declarations appear equivalent:

```kotlin
val displayName = string("display_name")
val sentRequests = hasMany<Friendship>("sent_requests")
```

Codegen currently reconstructs `displayName` and `sentRequests` by converting
the storage strings back to camel case. The result looks right only because
the Kotlin and storage vocabularies happen to contain the same words.

The flaw becomes visible as soon as they diverge:

```kotlin
val publicLabel = string("legacy_display_txt")
val outgoing = hasMany<Friendship>("sent_requests")
```

A Kotlin reader expects `publicLabel` and `outgoing` to be the application API.
Storage-derived codegen instead produces names based on `legacy_display_txt`
and `sent_requests`. Renaming a database identifier can then break application
source even when the domain API was intended to remain stable.

Retrofitting semantic meaning onto an ordinary `=` property also hides that
the property name is consumed by the schema DSL. The accepted declaration
form therefore uses Kotlin property delegation:

```kotlin
val publicLabel by string("legacy_display_txt")
val outgoing by hasMany<Friendship>("sent_requests")
```

The `by` keyword tells the reader and compiler that the builder participates in
the property's behavior. Kotlin passes the property metadata to the delegate,
so EntKt receives `publicLabel` and `outgoing` directly without a repeated API
name string or a reflective scan after construction.

### Inflection is an avoidable source of ambiguity

The current client generator lowercases an entity name and runs it through a
small English pluralizer. That convention cannot be correct for all domain
terms:

```text
Person      -> people
News        -> news
Equipment   -> equipment
Mouse       -> mice
Analysis    -> analyses
Status      -> statuses
```

It also has no principled answer for acronyms, product names, non-English
words, or intentionally singular repository terminology. A growing exception
dictionary would make generated source depend on global linguistic rules
rather than the schema in front of the reader.

EntKt already requires explicit storage names. Requiring one explicit client
property name is a small cost for deterministic generated APIs.

### Target types cannot name relationships

A schema can contain several edges to the same target:

```kotlin
val manager by belongsTo<User>("manager")
val mentor by belongsTo<User>("mentor")

val authoredPosts by hasMany<Post>("authored_posts")
val reviewedPosts by hasMany<Post>("reviewed_posts")
```

The semantic role belongs to the declaration. Names such as `loadUser`,
`loadPostEdges`, or names inferred from cardinality cannot distinguish these
relationships reliably.

## Goals

- Make generated Kotlin naming explicit and locally readable from the schema.
- Make declaration-name participation visible through Kotlin's `by` syntax.
- Remove all singularization and pluralization from codegen.
- Make schema class, client property, field, and edge names independent from
  storage identifiers.
- Use one naming source consistently across every generated artifact.
- Reject missing, ambiguous, invalid, or colliding names before source
  emission.
- Preserve storage metadata, inverse linkage, migrations, and driver behavior
  independently from public Kotlin naming.
- Give future generated surfaces, including `load{Name}`, a stable naming
  contract.

## Non-Goals

- Do not infer table or column names from Kotlin declarations in V1.
- Do not rename existing database objects automatically.
- Do not add an English inflection engine or customizable inflection registry.
- Do not infer relationship roles from target types or cardinality.
- Do not derive `clientName` from the class name or table name.
- Do not preserve old inferred names as deprecated aliases.
- Do not define edge-loading execution strategy.
- Do not add a repeated `apiName` / `clientName` string to fields or edges.
- Do not make index declaration-property names public APIs when no generated
  public surface corresponds to the index itself.

## Terminology

### Entity name

The simple name of the concrete `EntSchema` subclass:

```kotlin
class UserAccount : EntSchema(
    tableName = "user_accounts",
    clientName = "userAccounts",
)
```

The entity name is `UserAccount`. It produces entity-prefixed generated types
such as `UserAccount`, `UserAccountQuery`, and `UserAccountRepo`.

### Client name

The required lower-camel Kotlin identifier supplied as `clientName`:

```kotlin
clientName = "userAccounts"
```

It produces the exact generated client and configuration property:

```kotlin
client.userAccounts
privacy.userAccounts { /* ... */ }
validation.userAccounts { /* ... */ }
hooks.userAccounts { /* ... */ }
```

The generator does not transform it.

### Delegated declaration name

The name of a qualifying Kotlin delegated `val` bound to a registered schema
builder:

```kotlin
val primaryAuthor by belongsTo<User>("primary_author")
//  ^^^^^^^^^^^^^    declaration name
```

The declaration name is `primaryAuthor`.

### Storage name

An explicit string passed to a schema builder:

```kotlin
val primaryAuthor by belongsTo<User>("primary_author")
//                                           ^^^^^^^^^^^^^^^ storage name
```

Storage names participate in tables, columns, indexes, constraints, edge
lookup, joins, migration identity, and diagnostics about the database. They do
not name generated Kotlin APIs.

### Generated stem

The declaration name with only its first character converted to title case:

```text
posts            -> Posts
primaryAuthor    -> PrimaryAuthor
receivedRequests -> ReceivedRequests
```

Codegen may add a fixed framework prefix or suffix:

```text
query + Posts -> queryPosts
load  + Posts -> loadPosts
primaryAuthor + Id -> primaryAuthorId
```

These mechanical affixes are part of the generated API family. They are not
linguistic inflection. Codegen must not otherwise parse, translate, singularize,
pluralize, or normalize the declaration.

## Normative Naming Contract

### Schema classes name entity types

The concrete schema class simple name is the only source for entity and
entity-prefixed generated type names:

```kotlin
class UserAccount : EntSchema(
    tableName = "legacy_usr_acct",
    clientName = "accounts",
)
```

produces types such as:

```text
UserAccount
UserAccountQuery
UserAccountRepo
UserAccountCreate
UserAccountUpdate
```

Neither `legacy_usr_acct` nor `accounts` may rename those types.

The public schema-discovery path derives this identity from the class itself.
Low-level codegen carriers must not expose a second caller-controlled entity
name that can disagree with the schema class. Internal tests may construct a
resolved descriptor directly, but production generation has one authority.

Anonymous, local, or otherwise unnamed schema classes are rejected.

### `clientName` is required and exact

Every schema must explicitly provide its generated client property name:

```kotlin
abstract class EntSchema(
    val tableName: String,
    val clientName: String,
)
```

Example:

```kotlin
class Person : EntSchema(
    tableName = "people",
    clientName = "people",
)
```

The generator emits `client.people` exactly. There is no default, even when a
class or table name appears easy to pluralize.

`clientName` is shared by every generated client-like surface for that entity:

- application client and transaction client repositories;
- privacy and validation read clients;
- hook, privacy, validation, and interceptor configuration scopes;
- viewer/read surfaces and internal generated cross-repository references;
- generated documentation and diagnostics that spell a client property.

The value must be a supported lower-camel Kotlin identifier and unique across
the complete generated schema set. It may intentionally be singular or plural:

```kotlin
class Audit : EntSchema("audit_log", clientName = "audit")
class Person : EntSchema("people", clientName = "people")
class NewsItem : EntSchema("news_items", clientName = "news")
```

Table names must never be used as an implicit fallback. Coincidental equality
between a table and client name carries no additional meaning.

### Delegated field declarations name field APIs

Every registered scalar field builder must bind to exactly one qualifying
Kotlin delegated property. That declaration names all generated field APIs:

```kotlin
class Article : EntSchema("articles", clientName = "articles") {
    val publicTitle by string("legacy_title_txt")
    val publishedAt by time("published_timestamp")
}
```

produces:

```kotlin
Article.publicTitle
Article.publishedAt

article.publicTitle
article.publishedAt

create.publicTitle = "..."
update.publishedAt = now
```

It must not generate `legacyTitleTxt` or `publishedTimestamp`.

The declaration name applies consistently to entity properties, companion
columns, mutation fields, write candidates, patches, privacy and validation
items, hook contexts, projections, ordering, index helpers, and generated
diagnostic paths.

The storage string remains the physical column name and driver metadata key.

### Delegated edge declarations name edge APIs

Every registered edge builder must likewise bind to exactly one qualifying
Kotlin delegated property:

```kotlin
class Article : EntSchema("articles", clientName = "articles") {
    val writer by belongsTo<User>("author")
    val relatedStories by hasMany<Article>("related_stories")
}
```

produces declaration-derived surfaces such as:

```kotlin
Article.writer
article.edges.writer
queryWriter()
loadWriter()

Article.relatedStories
article.edges.relatedStories
queryRelatedStories()
loadRelatedStories()
```

It must not generate `author`, `queryAuthor`, `related_stories`, or a name
derived from `User` or `Article`.

The declaration name applies to companion edge references, `Edges` members,
traversal, graph loading, implicit FK API names, M2M mutation properties,
edge-specific generated types, and public explain/privacy/validation paths.

### Fixed ID naming

The framework-defined ID API remains `id`:

```kotlin
Person.id
person.id
```

An ID strategy's configured storage column remains storage metadata. The fixed
`id` member is explicit framework vocabulary, not a singularization or
storage-derived name. Schemas may not declare another field or edge whose
generated surfaces collide with it.

### Index and constraint naming

Index and constraint strings are storage identifiers. They do not become
generated client or entity names merely because an `IndexBuilder` is held in a
Kotlin property.

Current prefix index helpers are generated from the participating field API
names, which means they use field declaration names under this RFC:

```kotlin
val authorKey by long("author_id")
val createdAt by time("created_at")
val authorTimeline = index("idx_article_author_created", authorKey, createdAt)
```

may produce:

```kotlin
client.articles.indexes.authorKey(authorId)
client.articles.indexes.authorKey(authorId).createdAt(timestamp)
```

It does not produce `authorTimeline()` unless a future RFC explicitly defines
named-index helpers. Such a future API must use an explicit Kotlin-facing name,
not infer one from the storage index string.

## Schema-Author Naming Responsibility

Schema declaration names and `clientName` are public API commitments. Authors
must choose names that read naturally in their generated forms.

Recommended conventions:

- `clientName` describes the repository property exactly as application code
  should read it: `people`, `news`, `audit`, `userAccounts`.
- To-one relationships normally use a singular domain role: `author`,
  `manager`, `primaryContact`.
- To-many relationships normally use a plural or collective domain role:
  `posts`, `people`, `staff`, `receivedRequests`.
- Multiple relationships to one target use distinct roles: `manager` and
  `mentor`, `authoredPosts` and `reviewedPosts`.
- Relationship properties should not use FK-shaped names such as `authorId`.
  A scalar FK property may use that name; the relationship should name its
  domain role.
- Storage-oriented abbreviations and separators belong in storage strings,
  not Kotlin declarations.

These are documentation conventions, not grammar rules. EntKt validates
identifier safety and collisions, but it does not decide whether a word is
singular, plural, collective, English, or grammatically ideal.

## No Inflection

EntKt contains no generated-name inflection step.

```kotlin
class Person : EntSchema("people", clientName = "people")
class Sheep : EntSchema("sheep", clientName = "sheep")
class Equipment : EntSchema("equipment", clientName = "equipment")
class Analysis : EntSchema("analyses", clientName = "analyses")
```

Every client property above is emitted exactly as supplied.

Similarly:

```kotlin
val people by hasMany<Person>("person_links")
val sheep by hasMany<Sheep>("flock_members")
val news by hasMany<Article>("news_items")
```

generate `queryPeople`, `querySheep`, and `queryNews` exactly. Their target
types and storage strings do not participate in the method names.

The old `pluralize()` helper and every use of it in generated API construction
must be removed. No compatibility alias or configurable inflector replaces it.

## Storage Invariance

Changing only a Kotlin declaration is an API change and does not rename
storage:

```diff
- val displayName by string("display_name")
+ val publicName by string("display_name")

- val sentRequests by hasMany<Friendship>("sent_requests")
+ val outgoingRequests by hasMany<Friendship>("sent_requests")
```

The generated API changes; the database identifiers do not.

Changing only storage metadata is a storage/migration change and does not
rename generated Kotlin APIs:

```diff
  val displayName by string(
-     "display_name",
+     "legacy_display_txt",
  )

  val sentRequests by hasMany<Friendship>(
-     "sent_requests",
+     "outgoing_friend_requests",
  )
```

The Kotlin members remain `displayName` and `sentRequests`.

Changing only `clientName` is a generated client API change and does not alter
the entity type or table:

```diff
  class Person : EntSchema(
      tableName = "people",
-     clientName = "people",
+     clientName = "directory",
  )
```

`client.people` becomes `client.directory`; `Person` and table `people` remain
unchanged.

For an implicit `belongsTo`, storage and API synthesis remain separate:

```kotlin
val primaryAuthor by belongsTo<User>("primary_author")
```

produces:

```text
Relationship API: primaryAuthor
Implicit FK API:   primaryAuthorId
Default FK column: primary_author_id
```

The `Id` API suffix is mechanical. The default FK column continues to derive
from explicit edge storage metadata unless another schema API overrides it.

## Delegated Declaration Binding

### Eligible V1 declarations

V1 accepts a field or edge declaration name only from a property that is:

- a public Kotlin `val`;
- declared directly on the concrete schema or supported mixin class;
- delegated directly to one registered EntKt builder with `by`;
- bound exactly once through EntKt's delegate protocol;
- not mutable, inherited, computed, or delegated through another wrapper such
  as `lazy`;
- a supported lower-camel Kotlin identifier for generated APIs.

Canonical declarations qualify:

```kotlin
val title by string("article_title")
val author by belongsTo<User>("article_author")
val comments by hasMany<Comment>("article_comments")
```

The following do not:

```kotlin
val title = string("article_title")
private val author by belongsTo<User>("article_author")
var comments by hasMany<Comment>("article_comments")
val tags by lazy { manyToMany<Tag>("article_tags") }
val owner get() = belongsTo<User>("article_owner")
```

The ordinary `=` form constructs and registers a builder but does not bind a
generated declaration name. Finalization rejects an unbound registered builder
and suggests replacing `=` with `by`.

EntKt builders intentionally do not implement `setValue`, so a delegated `var`
does not compile. A non-public binding is rejected because a private schema
handle should not silently create a public generated member.

### Mixins

Mixins may contribute fields and indexes but not edges. A mixin field's direct
public stable `val` is its generated declaration name:

```kotlin
class Timestamps(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt by time("created_at")
    val updatedAt by time("updated_at")
}

class Article : EntSchema("articles", clientName = "articles") {
    val timestamps = include(::Timestamps)
}
```

The generated entity fields are `createdAt` and `updatedAt`; the inclusion
property `timestamps` does not prefix or rename them.

Binding occurs while each mixin instance is constructed, so an init-only
`include(::Timestamps)` remains supported and EntKt does not need a later
reflective scan of retained mixin instances. Nested mixins follow the same
rule. Two included mixins that contribute the same generated name fail
collision validation.

### Kotlin delegate protocol

Kotlin calls `provideDelegate` while constructing a delegated property and
passes its `KProperty` metadata. EntKt uses that compiler-provided interaction
to bind the builder to the property name.

Conceptually, each supported builder provides type-preserving operators like:

```kotlin
operator fun provideDelegate(
    thisRef: EntSchema,
    property: KProperty<*>,
): ThisBuilder {
    bindDeclaration(owner = thisRef, name = property.name)
    return this
}

operator fun getValue(
    thisRef: EntSchema,
    property: KProperty<*>,
): ThisBuilder = this
```

The actual operator may share an internal abstraction and must also support an
`EntMixin` receiver. Its return type remains the concrete field or edge builder
type. Consequently, modifier chaining, typed handles, `index(...)`,
`.field(handle)`, `Target::inverseEdge`, and `throughLink` / `throughEntity`
property references retain their existing static types.

Binding happens after the complete builder expression has been evaluated:

```kotlin
val author by belongsTo<User>("author")
    .field(authorId)
    .nullable()
```

No schema-wide property reflection, backing-field access, getter invocation,
annotation processor, compiler plugin, or repeated API-name string is needed
**to bind a name**.

#### Amendment: validation still reflects, deliberately

*Binding* is reflection-free as described. *Validation* is not, and the
implementation keeps one reflective pass on purpose.

`by lazy { string("col") }` never runs the builder, so nothing registers and
there is no unbound builder for finalization to reject — the column simply
disappears from the schema, the entity, and the migration, with no
diagnostic. That is the worst possible outcome for a naming contract whose
premise is that declarations are explicit, so the rejection this RFC promises
for wrapper delegates has to come from somewhere.

The implementation therefore walks the concrete schema class's declared
properties at finalization, and each mixin's at `include(...)`, looking for
builder-typed properties that never bound. The pass reads declared return
types only — it never invokes a getter, so inspecting a computed getter
cannot register a field as a side effect. It also supplies the
"declared directly on the concrete class" check, which binding alone cannot
express: a delegated property on a superclass binds indistinguishably from
one declared locally.

What this RFC removed is the reflective *name-capture* scan and its
storage-derived fallback. Names now come only from `provideDelegate`.

### Resolved metadata

Delegate binding records declaration identity on the builder before
finalization. Finalization folds it into resolved metadata alongside storage
identity:

```kotlin
data class Field(
    val storageName: String,
    val declarationName: String,
    // ...
)

data class Edge(
    val storageName: String,
    val declarationName: String,
    // ...
)
```

Exact model spelling may differ, but declaration names must be non-null before
codegen consumes finalized schemas.

### Exactly one binding

Every registered field or edge builder binds to exactly one eligible property.

Attempting to reuse a builder as two delegates is rejected at the second
binding:

```kotlin
private val sharedTitle = string("title")
val title by sharedTitle
val headline by sharedTitle // rejected: already bound to title
```

The diagnostic names both properties and tells the author to keep one canonical
binding. There is no reflection-order choice.

An unbound builder is rejected during schema finalization. Programmatic
registration without one eligible delegated property cannot produce a stable
public API name.

## Validation And Collisions

Validation happens before source emission. Reject at least:

- a missing or invalid entity class simple name;
- a missing, invalid, or duplicate `clientName`;
- a registered field or edge builder with no delegated binding;
- a builder bound as a delegate more than once;
- an ordinary `=` field/edge declaration;
- a private, mutable, inherited, computed, or wrapper-delegated declaration;
- an unsupported or reserved Kotlin identifier;
- a field, FK, edge, fixed framework member, generated function, or JVM
  signature collision;
- two generated stems that collide after the defined first-character title
  casing;
- a client name colliding on any generated application/read/configuration
  client surface.

Diagnostics name:

- the schema class;
- `clientName`, declaration property, or orphaned storage name;
- the generated artifact and member;
- both collision sources when applicable;
- the corrective action.

Example:

```text
Schema 'User': edge property 'sentRequests' generates
'querySentRequests' on UserQuery, which collides with another generated
member. Rename the Kotlin edge declaration; changing storage name
'sent_requests' will not change this API.
```

The generated-member manifest must cover client properties, query classes,
entity members and companions, entity `Edges`, mutation builders, lifecycle
items, index helpers, and configuration/read-client surfaces.

## Relationship Linkage

Typed relationship linkage remains handle-based:

```kotlin
val posts by hasMany<Post>("user_posts")

val author by belongsTo<User>("post_author")
    .inverse(User::posts)
```

The property reference identifies the declaration during resolution. Runtime
metadata may carry storage edge names for driver lookup. Generated API uses
`posts` and `author`.

Many-to-many linkage follows the same separation:

```kotlin
val tags by manyToMany<Tag>("article_tags")
    .throughLink<ArticleTag>(ArticleTag::article, ArticleTag::tag)
```

`tags` names the public relationship. `article_tags` and junction storage
names remain storage metadata.

## Interaction With Edge Loading

Edge-loading API and execution are separate designs. This RFC owns the
generated name. The accepted
[Generated Edge Loading API](../../possible-features/query/generated-edge-loading-api.md) RFC owns
the method shape and adopts `load{Name}`.

The public API uses the exact edge declaration name:

```kotlin
client.people.query {
    loadAuthoredPosts {
        loadComments()
    }
    loadReceivedRequests()
}
```

No target-type or cardinality inflection is involved. This naming contract
does not promise joins, set-based follow-up reads, chunks, or a driver-native
operation.

## Compatibility And Migration

This is an intentional source-breaking correction. EntKt is greenfield, so it
does not retain inferred aliases.

Every schema declaration must add `clientName`:

```diff
- class User : EntSchema("users")
+ class User : EntSchema(tableName = "users", clientName = "users")
```

Every generated field and edge declaration also migrates from assignment to
delegation:

```diff
- val displayName = string("display_name")
- val posts = hasMany<Post>("user_posts")
+ val displayName by string("display_name")
+ val posts by hasMany<Post>("user_posts")
```

For schemas whose declaration and storage names normalize to the same Kotlin
identifier, field and edge generated source remains mostly unchanged. Client
names remain unchanged only when the new explicit value matches the old
pluralizer output.

Divergent declarations intentionally rename generated source:

```kotlin
val publicLabel by string("legacy_display_txt")
val outgoing by hasMany<Friendship>("sent_requests")
```

```diff
- legacyDisplayTxt
- querySentRequests()
+ publicLabel
+ queryOutgoing()
```

No deprecated aliases are emitted. They would perpetuate ambiguous authority
and increase collision surfaces.

## Implementation Direction

1. Add required `clientName` to `EntSchema` and validate it as a lower-camel
   Kotlin identifier.
2. Remove the generated-name `pluralize()` helper and replace every call with
   resolved `clientName` metadata.
3. Make production `SchemaInput` derive the entity name from the concrete
   schema class and carry the explicit client name; remove caller-controlled
   entity-name overrides from the public path.
4. Add type-preserving `provideDelegate` / `getValue` support to every field
   and edge builder, including custom `FieldBuilder` subclasses and mixin
   fields. Bind and validate the declaration name during property construction.
5. Remove the reflective post-construction declaration-name *capture* scan and
   its alias
   fallback. Require every registered field and edge builder to have exactly
   one delegated binding by finalization.
6. Require non-null declaration names during schema/codegen validation; remove
   storage-derived fallbacks.
7. Centralize resolved schema, field, and edge names so generators cannot
   accidentally select storage identity for public source.
8. Replace public-name uses of `field.name`, `edge.name`, `toCamelCase(...)`,
   and `toPascalCase(...)` with declaration identity. Preserve storage names in
   metadata, migrations, predicate lowering, joins, and drivers.
9. Expand generated-member collision manifests before emitting newly reachable
   declaration-derived names.
10. Migrate all schemas, generated fixtures, examples, and public documentation
    to explicit client names and delegated field/edge declarations.

Do not opportunistically rename database objects while implementing this RFC.

## Acceptance Criteria

- Every schema has one explicit, valid, unique `clientName`.
- Every generated client/configuration property uses it exactly.
- Production entity type names come only from schema class names.
- Every registered field and edge is bound through `by` exactly once.
- Ordinary `=` field/edge declarations fail with an actionable diagnostic.
- Delegate access preserves each builder's complete static type.
- Every public generated field and edge identifier uses declaration identity.
- Storage/runtime/migration metadata continues to use explicit storage names.
- Codegen performs no English inflection, target-derived naming, or
  storage-to-Kotlin normalization for public APIs.
- Renaming only a declaration renames Kotlin source but not storage.
- Renaming only storage changes storage metadata but not Kotlin source.
- Renaming only `clientName` changes client/configuration properties but not
  entity types or storage.
- Invalid binding and collisions fail before source emission with actionable
  diagnostics.
- Direct production codegen entry points cannot bypass validation.

## Test Requirements

### Schema and client names

- schema class `Person` produces `Person`-prefixed artifacts;
- table `legacy_people_tbl` does not affect entity names;
- `clientName = "people"` produces every expected `people` client/config
  property;
- irregular, uncountable, acronym, and non-English client names are emitted
  exactly;
- missing, invalid, duplicate, and reserved client names reject;
- no pluralizer helper or generated-name use remains;
- low-level production generation cannot override the schema class name.

### Delegate binding

- direct public field and edge `val x by builder(...)` declarations bind their
  names during construction;
- storage and declaration names may differ;
- ordinary `=`, private, mutable, inherited, computed, and wrapper-delegated
  declarations reject;
- binding one builder to a second property rejects with both property names;
- binding does not scan properties, invoke getters, or register extra
  declarations;
- mixin field declarations bind in the mixin without a host-property
  prefix;
- init-only and nested mixin inclusion remains supported;
- nested mixin and host/mixin collisions reject deterministically;
- field and edge binding coexist without identity confusion;
- field modifiers, explicit FK handles, indexes, inverse references, and M2M
  through references retain their concrete types.

### Generated names

Use a schema containing at least:

```kotlin
class Article : EntSchema("legacy_article_tbl", clientName = "stories") {
    val publicTitle by string("legacy_title_txt")
    val primaryAuthor by belongsTo<User>("primary_author")
    val authoredPosts by hasMany<Post>("written_posts")
    val reviewedPosts by hasMany<Post>("reviewed_posts")
    val people by manyToMany<Person>("person_links")
}
```

Assert declaration/client names across:

- entity fields and companion references;
- repositories and every client/read/configuration surface;
- create/update builders, patches, candidates, lifecycle items, and hooks;
- entity `Edges`, query traversal, current eager loading, and future explicit
  loading;
- implicit and explicit FK properties;
- M2M mutation helpers and pending-edge views;
- prefix index helpers;
- public explain and diagnostic paths.

Assert storage metadata still contains `legacy_article_tbl`,
`legacy_title_txt`, `primary_author`, `written_posts`, `reviewed_posts`, and
`person_links`.

### Collisions

- duplicate client names across schemas;
- declaration-derived field versus fixed entity/mutation members;
- edge versus field/FK companion member;
- `query{Name}` / `load{Name}` versus fixed/generated query members;
- entity `Edges` and M2M mutator collisions;
- two names whose title-cased stems collide;
- client collisions on application, read, and configuration clients;
- diagnostics distinguish class, client, declaration, and storage names.

### Compile and integration coverage

- generated Kotlin and Java-facing APIs compile for divergent names;
- generated application, privacy, validation, transaction, and viewer clients
  resolve the explicit client property;
- declaration-derived queries and mutations bind the correct storage columns;
- traversal/loading attaches through the correct storage relationship;
- inverse and M2M relationships resolve through unchanged metadata;
- index helpers use field declaration names and the same storage index;
- migration output is unchanged when only Kotlin declarations or `clientName`
  change.

## Documentation Requirements

Update schema, query, mutation, privacy, validation, hooks, codegen, migration,
and getting-started documentation to state:

- schema classes name entity-prefixed generated types;
- `clientName` is required and emitted exactly;
- `by` marks field and edge property names as generated-API commitments;
- ordinary `=` constructs an unbound builder and is not a valid generated
  field/edge declaration;
- storage strings are storage/runtime/migration metadata;
- EntKt never pluralizes or singularizes generated names;
- schema authors must name edges so `query{Name}`, `load{Name}`, and
  `edges.{name}` read naturally;
- declaration, client, and storage renames have independent consequences.

Examples must include irregular client names and at least one
declaration/storage mismatch so the separation is visible.

## Resolved Decisions

1. EntKt performs no singularization or pluralization in code generation.
2. Schema class names exclusively determine entity-prefixed generated types.
3. Every schema explicitly declares a required `clientName`; there is no
   inferred default.
4. Delegated field and edge Kotlin properties, not storage strings, name
   generated APIs.
5. Target entity types never determine relationship API names.
6. Fixed framework `id` naming and mechanical API affixes are not inflection.
7. Index and constraint strings remain storage names; prefix helpers use field
   declaration names.
8. V1 binds only direct public stable `val x by EntKtBuilder(...)`
   declarations on supported schema/mixin classes.
9. Every registered field and edge has exactly one delegated binding and one
   declaration name.
10. Class, client, declaration, and storage renames have independent effects.
11. Old inferred aliases are not retained.
12. Edge-loading syntax and execution remain separate concerns; any generated
    `load{Name}` method follows this naming contract.

## Related RFCs

- [Typed Schema Handles](../../possible-features/schema/typed-schema-handles.md)
- [Field-Backed FK Declaration Names](../edge-mutation/06-field-backed-fk-declaration-names.md)
- [Generated Member Name Collisions](../edge-mutation/07-generated-member-name-collisions.md)
- [To-One FK Mutation And Nullability](../edge-mutation/02-to-one-assignment-nullability.md)
- [Generated Edge Loading API](../../possible-features/query/generated-edge-loading-api.md)
- [Set-Based Eager Graph Loader](../../possible-features/query/set-based-eager-graph-loader.md)
