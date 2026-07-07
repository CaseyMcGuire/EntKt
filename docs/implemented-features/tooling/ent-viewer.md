# RFC: Ent Viewer

## Status

**Implemented** (2026-07-06). The sections below are the original design
contract, kept as a record. User-facing docs: [Ent Viewer](../../11-ent-viewer.md).

### As-built notes

The implementation followed this contract closely:

- `io.entkt:ent-viewer`, package `entkt.viewer`, exactly the reserved routes,
  read-only, kotlinx.html rendering with a Kotlin-generated stylesheet.
- The RFC's `EntViewerEntity` sketch typed `client: EntClient` — pseudo-code,
  since the generated client type is application-specific. As built, the core
  is generic over the client type (`EntViewerEntity<C>`, `EntViewerRegistry<C>`)
  and the generated registry bridges `withPrivacyContext`, so all
  generated-client knowledge lives in generated code.
- `ColumnMetadata.sensitive` landed first (the required metadata change);
  adapters emit pre-redacted cells without reading the sensitive property and
  without disclosing null-ness.
- Filter/order translation lives once in the core (`EntViewerFilters`,
  op-per-type + value parsing + enum-name validation); generated adapters
  stay thin and call generated repo terminals only (`visibleAll`,
  `visibleByIdOrNull`) — privacy-denied, missing, and unparseable ids are
  the same 404.
- Edge links: belongsTo via the row's FK value; hasMany via an edge-scoped
  filter on the target list (`?f=fk:eq:id`); M2M rendered as plain text in
  V1. **Deviation:** the RFC's detail sketch links to-one edges directly to
  the target detail route — that holds for belongsTo, but hasOne (FK on the
  target) links to the filtered target list instead, since resolving the
  target id would need an extra query per edge. Filter links are stripped
  (rendered as plain text) when the target FK column isn't filterable
  (sensitive or extra-redacted), so a rendered link is never a guaranteed
  400. Edge row counts from the layout sketch were deferred (a count query
  per edge per detail render).
- Pagination is privacy-coherent, decided after adversarial review: entities
  without load privacy use an exact `pageSize + 1` probe; entities with load
  privacy page over raw windows with `hasNext` unknown (navigation offered
  unconditionally, bannered) because visible-count-derived next links are an
  oracle over privacy-denied rows. The generated adapter pre-rejects page
  sizes at or above the visible-scan cap (`visibleOverfetchLimit`) as a
  deterministic 400 (the `visibleAllOrError` cap error remains as a
  backstop), never a silent truncation. Page depth is capped and
  offset math is overflow-safe. Read-interceptor rejections
  (`EntQueryRejectedException`) render as controlled 400s.
- The stylesheet uses the kotlin-css DSL (`CssBuilder`), per the RFC's
  rendering section; kotlinx.html renders all pages with escaping-by-default
  (the stylesheet is the single `unsafe` block).
- No `:ent-viewer-spring` module yet: example-spring mounts the viewer with
  the thin-wrapper pattern directly (`EntViewerEndpoint`), validated by
  MockMvc tests, which is the RFC's stated first integration goal; a
  packaged Spring adapter can still come later.
- Pagination is prev/next via a size+1 fetch — no count queries; page size
  clamped to 200.

## Summary

Add an optional `ent-viewer` Gradle module that lets applications mount a
configurable endpoint for inspecting generated ents in a browser.

The first version should be read-only and server-rendered with Kotlin-native
rendering tools:

- `kotlinx.html` for HTML
- a Kotlin CSS DSL (`kotlinx-css` / CSS-in-Kotlin) for styles

The viewer is a framework-provided debug/admin surface, not a second generated
application API. It should use the same generated ent metadata, privacy context,
read interceptors, and driver behavior as the application unless the user opts
into an explicitly dangerous bypass.

## Motivation

entkt already generates rich schema and query metadata, but applications do not
have an easy way to inspect stored ents, follow edges, or verify generated
behavior while developing.

A small viewer would help users answer:

- what entities and columns exist?
- which fields are nullable, unique, indexed, sensitive, or relational?
- what rows are currently visible under the configured privacy context?
- what does one row look like?
- which edges can I follow from this row?
- which read interceptors or soft-delete filters affect the result?

The viewer should follow the principle of least surprise:

- opt-in only
- read-only first
- privacy-respecting by default
- `.sensitive()` fields redacted by default
- no hidden all-powerful viewer
- no raw driver escape hatch unless the API name says it is dangerous

## Proposed Module Shape

Gradle module:

```text
:ent-viewer
```

Published artifact:

```text
io.entkt:ent-viewer
```

Package:

```kotlin
entkt.viewer
```

The module should stay mostly framework-neutral. It can render HTML/CSS and
handle a small request/response abstraction. Framework adapters can be added
later if needed:

```text
:ent-viewer-spring
:ent-viewer-ktor
```

That keeps the core viewer usable without forcing a web stack dependency into
entkt itself.

V1 should implement the core renderer/request handler first, with no Spring or
Ktor dependency. A Spring adapter should be the first framework-specific wrapper
after the core is working, because the repository already has a Spring example
to validate integration against. Ktor can follow later as the same thin adapter
pattern.

## Example API

One possible shape:

```kotlin
val viewer = EntViewer(
    client = client,
    registry = GeneratedEntViewerRegistry,
) {
    path = "/_ent"

    authorize { request ->
        request.user?.isAdmin == true
    }

    privacyContext { request ->
        PrivacyContext(Viewer.User(request.user.id))
    }

    entities {
        exclude(Sessions)
    }
}
```

Framework integration can adapt this to a real endpoint:

```kotlin
get("/_ent/{...}") { call ->
    viewer.handle(call.toEntViewerRequest()).writeTo(call)
}
```

Spring-style integration could be a thin wrapper around the same core:

```kotlin
@Bean
fun entViewerRoutes(client: EntClient): EntViewerEndpoint =
    EntViewerEndpoint(client, GeneratedEntViewerRegistry) {
        path = "/_ent"
        authorize { request -> request.principal?.isAdmin == true }
    }
```

## Routes

V1 should reserve top-level viewer paths for viewer tools and put entity rows
under an explicit `entities` namespace:

```text
/_ent
/_ent/schema
/_ent/entities
/_ent/entities/{type}
/_ent/entities/{type}/{id}
```

Examples:

```text
/_ent
/_ent/schema
/_ent/entities
/_ent/entities/user
/_ent/entities/user/123
/_ent/entities/post
/_ent/entities/post/456
```

Route behavior:

- `/_ent` renders the viewer home.
- `/_ent/schema` renders schema metadata.
- `/_ent/entities` renders the entity type index.
- `/_ent/entities/{type}` renders a paginated list for that entity type.
- `/_ent/entities/{type}/{id}` renders one entity row and its followable edges.
- unknown entity type routes return 404.
- unknown or privacy-denied ids should use the same not-found behavior as the
  generated read API, avoiding existence disclosure.

`{type}` should be a stable generated route name, not the table name by
default. The least surprising default is the singular generated entity name in
  lower camel case (`User` -> `user`, `StudyAsset` -> `studyAsset`). A future
  config hook can override route names if an application needs kebab-case or table
  names, but V1 should not expose multiple route naming schemes by default.

## Detail View

The detail page should separate scalar fields from relationships.

Recommended layout:

```text
User #123

Fields
  id              123
  name            Casey
  email           casey@example.com
  password_hash   ***

Edges
  posts           14 posts        -> /_ent/entities/post?user=123
  profile         Profile #99     -> /_ent/entities/profile/99
```

Fields:

- show declared columns and field-backed foreign-key columns
- redact `.sensitive()` values
- render `null` distinctly from an empty string
- keep JSON/native values readable but compact in V1

Edges:

- render separately from fields
- are clickable when the target is visible in the viewer registry and not
  excluded
- to-one edges link directly to the target detail route when the related row is
  visible
- to-many and M2M edges link to the target entity list with an edge-scoped
  filter/traversal parameter
- privacy still applies after navigation; an edge link must not imply the target
  row will be readable

V1 can render an edge as disabled/plain text when the viewer cannot build a safe
generated traversal for it. It should not fall back to a raw driver join just to
make a link work.

The exact framework adapter API can wait. The core contract is that the viewer
receives:

- an `EntClient`
- generated viewer adapters for each entity
- an authorization callback
- a privacy-context callback
- entity exclusion / redaction configuration

All generated entities are included by default. V1 should expose only an
`exclude(...)` API, not both `include(...)` and `exclude(...)`. Installing the
viewer means "make generated ents viewable under the configured authorization
and privacy context"; hiding specific entities should be the explicit action.
An allow-list mode can be added later if a concrete need appears, but the first
API should avoid two overlapping ways to answer the same visibility question.

## Codegen Opt-In

Viewer adapters should be generated only when explicitly enabled:

```kotlin
entkt {
    viewer.set(true)
}
```

Default is false. When disabled, codegen emits no viewer files and applications
do not need the `ent-viewer` dependency.

This keeps the base generated surface small and avoids a surprising compile-time
dependency on optional viewer types. When enabled, generated code may reference
`EntViewerRegistry`, `EntViewerEntity`, `EntViewerRow`, and related viewer
types from the `ent-viewer` module.

## Generated Viewer Registry

The viewer should not rely on reflection over generated repo properties. It also
should not use the low-level `Driver.query(...)` by default, because that would
bypass generated privacy, read interceptors, result decoding, and future loader
behavior.

Instead, codegen should optionally emit a viewer registry:

```kotlin
object GeneratedEntViewerRegistry : EntViewerRegistry {
    override val entities: List<EntViewerEntity<*>> =
        listOf(UserViewerEntity, PostViewerEntity, CommentViewerEntity)
}
```

Each generated adapter owns the typed bridge from dynamic viewer requests to the
generated API:

```kotlin
interface EntViewerEntity<E : Any> {
    val schema: EntitySchema
    val displayName: String
    val routeName: String
    val columns: List<EntViewerColumn<E, *>>
    val edges: List<EntViewerEdge<E>>

    fun list(
        client: EntClient,
        request: EntViewerListRequest,
    ): EntViewerPage

    fun get(
        client: EntClient,
        id: String,
    ): EntViewerRow?
}
```

Generated implementations can call the normal typed repos:

```kotlin
client.users.query {
    where(parsedFilterPredicate)
    orderBy(parsedOrder)
    limit(pageSize)
}.allOrError()
```

That preserves the normal read path:

- privacy context
- read privacy
- read interceptors
- soft-delete filters
- result decoding
- generated entity construction

## Privacy Contract

Read privacy must be enforced by default. The viewer is a different
presentation surface for the same generated ent APIs, not a privileged backdoor
around them.

For V1:

- list pages use generated query terminals that run normal read privacy
- row-detail pages use generated read APIs that run normal read privacy
- edge traversal uses generated edge/query APIs that run normal read privacy for
  the traversed entity
- authorization controls access to the viewer endpoint, not access to rows
- the supplied `privacyContext` controls which rows are visible
- privacy-denied rows are omitted or reported the same way the generated API
  would report them

V1 must use generated entities only. It should not expose a raw row-map mode or
call `Driver.query(...)`, `Driver.byId(...)`, or another raw driver method for
user-visible reads.

If a later debugging tool needs raw driver reads, it should be a separate
dangerously named API that documents which protections are bypassed.

If a future admin/debug mode needs to ignore privacy, that should be a separate
opt-in:

```kotlin
bypassPrivacy_DANGEROUS(reason = "internal admin viewer")
```

That opt-in should be unnecessary for the normal viewer. Most applications
should mount the viewer with an admin-only endpoint authorization check and a
normal application `PrivacyContext`; the data displayed should still be the data
that viewer is allowed to read.

## Sensitive Fields

The viewer must use `.sensitive()` as the default redaction source. Users should
not have to repeat:

```kotlin
redact(User.passwordHash)
redact(User.resetToken)
```

if the schema already declares:

```kotlin
val passwordHash = string("password_hash").sensitive()
val resetToken = string("reset_token").nullable().sensitive()
```

Default behavior:

- sensitive fields remain visible as fields, so users can tell they exist
- sensitive values render as `***`
- sensitive values are omitted or redacted from machine-readable payloads
- sensitive values are not embedded in HTML attributes
- sensitive columns are not filterable by default
- sensitive columns are not orderable by default
- sensitive bind values are not exposed in diagnostics

Applications may add extra redaction for legacy or computed values:

```kotlin
redaction {
    extra("users", "legacy_secret_column")
}
```

Opting into sensitive filtering should require an explicitly dangerous name, if
it is ever supported:

```kotlin
allowSensitiveFilters_DANGEROUS(reason = "local development only")
```

### Required Metadata Change

Today `.sensitive()` exists on schema fields and generated entity `toString()`
uses it for redaction, but runtime `ColumnMetadata` does not carry the flag.

Before implementing the viewer, add:

```kotlin
data class ColumnMetadata(
    ...
    val sensitive: Boolean = false,
)
```

and populate it from codegen for:

- declared fields
- mixin fields
- field-backed foreign keys whose backing field is `.sensitive()`

This makes `.sensitive()` a framework-wide display contract, not only a
`toString()` contract.

## Rendering

The core module should render server-side HTML with `kotlinx.html`:

```kotlin
html {
    head {
        title("entkt viewer")
        style { unsafe { +viewerCss } }
    }
    body {
        nav { /* entities */ }
        main { /* table / detail / edge view */ }
    }
}
```

CSS should be generated from Kotlin as well, rather than requiring a frontend
build step:

```kotlin
val viewerCss = buildString {
    // kotlinx-css / CSS-in-Kotlin output
}
```

The UI should be utilitarian and dense:

- schema/entity navigation
- table view with pagination
- row detail view
- edge links
- compact filter and order controls
- redaction markers for sensitive fields
- no marketing-style landing page

## Read-Only V1

V1 should not support writes.

Write support would need to make clear decisions about:

- create/update/delete privacy
- validators
- hooks
- transaction requirements
- soft-delete vs physical delete
- dangerous operations
- CSRF protection
- audit trails

Those concerns are real but separate. A read-only viewer is immediately useful
and much easier to reason about.

## Query Behavior

The viewer should start with a small, explicit filter model:

```kotlin
data class EntViewerFilter(
    val column: String,
    val op: EntViewerFilterOp,
    val value: String?,
)
```

Generated adapters parse filters through generated column metadata. Unsupported
filters should fail before query execution with a user-facing message.

Default filter/order support:

- scalar non-sensitive fields: allowed where existing query column helpers
  support the operation
- nullable fields: `is null` / `is not null`
- JSON fields: display only; filtering deferred
- pgvector/native fields: display only; filtering deferred
- sensitive fields: display redacted; no filtering/order by default

Pagination should be required. The viewer must not issue unbounded table scans
by default.

## Security Model

The viewer must be safe by construction:

- not installed by default
- no default public route
- application must supply authorization
- privacy context is application-supplied
- no default privacy bypass
- no write support in V1
- sensitive fields redacted by default
- all generated entities included by default, with explicit entity exclusions
- endpoint should be easy to disable in production

If a future API supports bypassing privacy, the name must be explicit:

```kotlin
bypassPrivacy_DANGEROUS(reason = "internal admin viewer")
```

The viewer should not create its own `Viewer.AllPowerful` or equivalent.
All-powerful access belongs to a separate, explicit privacy-bypass API.

## Relationship To Existing Features

- Uses `EntClient.SCHEMAS` / generated `EntitySchema` metadata for schema shape.
- Requires `ColumnMetadata.sensitive` before sensitive-field redaction is
  reliable at runtime.
- Uses generated repos rather than low-level `Driver.query(...)` by default so
  read privacy and interceptors apply.
- Complements query diagnostics; query-plan explanations are deferred from V1.
  A later diagnostics integration may link to explain output where available,
  but the viewer should not replace the explain APIs.
- Complements schema explain/validation; it is an application runtime viewer,
  not a schema compiler.

## Non-Goals

- No writes in V1.
- No automatic privacy bypass.
- No GraphQL server.
- No OpenAPI generation.
- No frontend framework requirement.
- No client-side build pipeline.
- No schema editing.
- No migration management.
- No raw SQL console.
- No sensitive-field filtering by default.

## V1 Decisions

- Use generated entity APIs only; no raw row-map mode.
- Keep sensitive fields visible, but redact their values as `***`.
- Defer query-plan explanations to a later diagnostics integration.
- Implement `ColumnMetadata.sensitive` before the viewer so redaction is
  reliable at runtime.
