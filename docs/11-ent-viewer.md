# Ent Viewer

An optional, read-only, server-rendered surface for inspecting generated ents
in a browser: what entities and columns exist, which rows the configured
privacy context can see, what one row looks like, and which edges it can
follow. It is a debug/admin presentation layer over the normal generated read
path — never a privileged backdoor around it.

## Install

Enable adapter generation and add the viewer module:

```kotlin
// build.gradle.kts
entkt {
    packageName.set("com.example.ent")
    viewer.set(true)
}

dependencies {
    implementation("io.entkt:ent-viewer-core:0.1.0-SNAPSHOT")
    // Spring Boot apps: auto-mounts the EntViewer bean you declare.
    implementation("io.entkt:ent-viewer-spring:0.1.0-SNAPSHOT")
}
```

With `viewer.set(true)`, the generated `GeneratedEntViewerRegistry` makes
your entities available to the viewer. The default is `false`, so projects
that do not use the viewer need no viewer dependency.

## Mount

The core is framework-neutral — adapt your framework's request into
`EntViewerRequest`, hand it to `EntViewer.handle`, write the response out:

```kotlin
val viewer = EntViewer(client, GeneratedEntViewerRegistry) {
    path = "/_ent"
    authorize { request -> request.principal.isAdminOfSomeKind() }
    privacyContext { request ->
        PrivacyContext(Viewer.User((request.principal as Admin).userId))
    }
    entities { exclude("session") }
    redaction { extra("users", "legacy_secret_column") }
}
```

**Spring Boot:** with `io.entkt:ent-viewer-spring` on the classpath, declaring
the `EntViewer` bean is all it takes — the auto-configuration mounts it at
its configured path. The module deliberately creates no viewer of its own:
the classpath alone changes nothing, and the security-critical configuration
stays in your application. Bridge your auth into the viewer's principal with
an optional resolver bean (defaults to the servlet `userPrincipal`):

```kotlin
@Bean
fun entViewer(client: EntClient): EntViewer<EntClient> =
    EntViewer(client, GeneratedEntViewerRegistry) {
        path = "/_ent"
        authorize { it.principal != null }
        privacyContext { ... }
    }

@Bean
fun entViewerPrincipalResolver(auth: AuthContext): EntViewerPrincipalResolver =
    EntViewerPrincipalResolver { auth.userId }
```

(example-spring's `EntViewerEndpoint` is exactly this pattern.) Other
frameworks adapt their request by hand — see the `EntViewerRequest` shape
above; pass the raw, still percent-encoded request path.

## Routes

```
/_ent                       viewer home
/_ent/schema                searchable schema index (?q= matches names, tables, columns, edges)
/_ent/schema/{type}         one entity's schema (columns, edges, indexes)
/_ent/entities              entity type index
/_ent/entities/{type}       paginated, filterable list
/_ent/entities/{type}/{id}  row detail + followable edges
```

`{type}` is the generated entity name in lower camel case (`User` -> `user`,
`StudyAsset` -> `studyAsset`).

## Gating access

Layered, each answering a different question:

```kotlin
// 1. Who reaches the viewer: the authorize callback (deny-all by default).
authorize { request -> (request.principal as? AppUser)?.isAdmin == true }

// 2. Defense in depth (Spring Security): reject in the filter chain first.
http.authorizeHttpRequests { it.requestMatchers("/_ent/**").hasRole("ADMIN") }

// 3. Whole environments: no bean, no routes — a complete kill switch.
@Bean @Profile("dev", "staging")
fun entViewer(client: EntClient): EntViewer<EntClient> = ...
```

Which *rows* are visible is `privacyContext`'s job, not `authorize`'s; which
*entities* exist is `entities { exclude(...) }`; which *columns* show values
is `.sensitive()` / `redaction { extra(...) }` — see below.

## Security model

- **Deny-all until configured, cloaked when denied.** `authorize` defaults
  to `{ false }`, and an unauthorized request — any method, before any other
  check — gets a 404 indistinguishable from an unmapped route (the Spring
  adapter surfaces Boot's own native not-found), so the viewer's existence
  is never disclosed to unauthorized callers. Authorization gates the
  *endpoint*; it grants nothing about rows. If you want 401/redirect
  semantics instead, gate in front with Spring Security.
- **Rows come from the privacy context.** Every read runs under the
  per-request `privacyContext` through the generated client's
  `withPrivacyContext`, using the canonical read terminals. A list window
  containing any privacy-denied row renders as an explicitly
  privacy-filtered empty page (see below); the detail route reads with
  `findById(id).visibleOrNull()`, so a privacy-denied, missing, or
  unparseable id is the same 404 — no existence disclosure. The default
  context is `Viewer.Anonymous` (fail-closed).
- **Read-only.** Non-GET requests are 405, and the viewer exposes no create,
  update, delete, or arbitrary-query API.
- **Sensitive fields stay sensitive.** `.sensitive()` columns are visible as
  fields but render as `***`; the generated adapters never materialize the
  value (null-ness included), and sensitive columns are not filterable or
  orderable. `redaction { extra(table, column) }` adds legacy columns to the
  same rules; nothing can remove a schema-declared redaction.
- **Exclusion, not inclusion.** All generated entities are viewable by
  default; `entities { exclude(...) }` hides one, and an excluded entity is
  indistinguishable from an unknown route. There is deliberately no
  `include(...)` allow-list in V1.

## Lists, filters, ordering, pagination

List pages accept repeatable `f=column:op[:value]` filters (also produced by
the page's filter form), `order=column&dir=asc|desc` (strict; anything else
is a 400), and `page`/`size` (default 50, max 200; page depth is capped) —
pagination is always applied and bounded; the viewer never issues an
unbounded scan.

Pagination is privacy-coherent and all-or-nothing per window. A page whose
fetched window contains no denied rows shows the full window with exact
next-page detection (a `size + 1` probe). If **any** row in the window is
denied by LOAD privacy, the strict read fails and the viewer renders an
explicitly privacy-filtered *empty* page with a banner — it never shows a
partial window, and further navigation is offered unconditionally, since
deriving next-link presence from visible rows would let it disclose
denied-row information; later pages may still contain visible rows. For
full listings of a privacy-guarded entity, run the viewer under a
bypass-scoped context (`privacyContext { PrivacyContext(Viewer.PrivacyBypass(...)) }`).
Read-interceptor rejections (tenant guards and similar) render as
controlled 400s naming the interceptor.

Supported ops by type: comparison (`eq,neq,gt,gte,lt,lte`) for numeric,
string, and time columns; `contains`/`prefix`/`suffix` for strings;
`eq`/`neq` for bool, uuid, and enum (validated against constant names);
`isnull`/`notnull` for nullable columns. JSON, pgvector, and bytes columns
are display-only. Anything unsupported fails as a 400 with a message before
any query executes.

## Edges

On a detail page, `belongsTo` edges link straight to the target row (via the
FK value), and `hasOne`/`hasMany` edges link to the target list filtered by
the target's FK column. M2M edges render as plain text in V1 — the viewer
does not link them yet. Links respect entity exclusions, and privacy still
applies after navigation.
