# RFC: SQL-Shaped Query Core

## Status

Accepted architectural direction as of 2026-08-18. This is not implemented.

## Summary

EntKt queries should be type-safe relational queries whose physical statement
boundary is visible at the call site.

The defining rule is:

> An executable EntKt query terminal issues at most one relational statement
> for the query it represents, and exactly one when execution reaches storage.
> A SQL driver executes that relational statement through one SQL/JDBC
> statement attempt.

EntKt must not implement an ordinary query by silently issuing follow-up edge
queries, splitting the query into parameter chunks, creating temporary tables,
or retrying the statement. A query rejected during compilation or by an
interceptor can execute zero statements. Dry-run explain methods also execute
zero statements.

Application-authored callbacks remain ordinary application code. A privacy
rule or another callback explicitly given a read client may execute another
query; that nested query has its own statement boundary and is outside the
statement represented by the original query. Read interceptors do not receive
a client, and capturing one in an interceptor closure remains unsupported.

The existing `with{Edge}` API remains part of the query. It does not become a
multi-statement loader. Instead, requested relationships are lowered into
nested relational projections within the same statement:

- to-one relationships use root-preserving joined or correlated projections;
- to-many relationships use independent nested collection projections;
- many-to-many relationships join the junction and target relation inside one
  nested collection projection;
- nested `with{Edge}` calls recursively extend those projections.

The physical nested-row transport is a driver concern. PostgreSQL may use
ordinary aliased columns, correlated or `LATERAL` subqueries, ordered JSON or
array aggregation, or another lossless one-statement representation. JSON is
not part of the public EntKt query model.

## Motivation

A developer should be able to look at an EntKt query and understand its
relational meaning and framework statement count:

```kotlin
val users = client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(20)

    withProfile()
    withPosts {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
        limit(5)
    }
}.all()
```

This should mean one relational statement that:

1. selects the first 20 matching users;
2. projects at most one profile for each selected user;
3. projects the first five matching posts for each selected user;
4. returns enough typed data to materialize the requested entity graph.

It should not secretly mean:

```text
1 query for users
1 query for profiles
1 query for posts
N more queries for nested relationships
```

Applications can already write explicit set-based fetching when they want
multiple statements:

```kotlin
val users = client.users.query { where(User.active eq true) }
    .all()
    .getOrThrow()

val posts = client.posts.query {
    where(Post.authorId `in` users.map { it.id })
}.all().getOrThrow()
```

EntKt may eventually offer an explicitly named prefetch utility, but ordinary
query syntax must not hide that orchestration.

This boundary gives EntKt a useful position between a general SQL DSL and an
ORM with strategy-dependent query counts: generated schema knowledge removes
join, decoding, and entity boilerplate without obscuring SQL semantics or
statement boundaries.

## Current Architecture

The current read path is only partly statement-shaped.

Root reads already use one driver call. The generated query freezes predicates,
ordering, and bounds, then calls a table-oriented operation equivalent to:

```kotlin
driver.query(table, predicates, orderBy, limit, offset)
```

`has`, `EdgeRef.exists()`, and generated `query{Edge}` traversal already lower into
`EXISTS`, `IN (subquery)`, or equivalent expressions inside that root
statement.

`with{Edge}` crosses the intended boundary. After the root statement and root
LOAD privacy, generated code invokes `loadEdges`. Direct relationships issue a
target query, many-to-many relationships issue a junction query and a target
query, and grouped nested edges may recursively issue one query per parent
group.

The current `Driver` surface is also narrower than a relational core. It has
parallel methods for full rows, by-ID lookup, count, exists, aggregates, and
explain. Its predicates contain entity-edge nodes that PostgreSQL resolves
using the registered graph. There is no common relation/projection/alias model.

This RFC replaces that boundary with one immutable relational statement plan.
Generated entity and edge APIs lower into the plan before it reaches the
driver.

## Goals

- Make every ordinary EntKt query terminal execute at most one relational
  statement for the query it represents.
- Keep `with{Edge}` ergonomic while giving it a fixed one-statement meaning.
- Preserve ordinary SQL semantics for predicates, ordering, bounds,
  aggregation, joins, and traversal.
- Preserve root entity cardinality when `with{Edge}` materializes nested
  relationships.
- Avoid Cartesian multiplication between independent to-many projections.
- Preserve `EdgeState.Unloaded`, `Loaded(null)`, `Loaded(emptyList())`, and
  loaded-value distinctions.
- Preserve per-parent edge predicates, ordering, offset, and limit.
- Preserve strict eager LOAD privacy and edge-local `filterVisible()` result
  semantics.
- Make interceptor contributions and nested relationship lowering visible in
  explain output.
- Use generated schema information for aliases, correlations, decoding, and
  capability validation.
- Reject unsupported one-statement shapes before storage I/O.
- Keep the relational plan and nested transport driver-neutral.

## Non-Goals

- Do not promise that application callbacks execute no additional statements.
- Do not promise that one SQL statement is always the cheapest possible plan.
- Do not add implicit lazy loading.
- Do not add a hidden multi-statement fallback.
- Do not make JSON, JSONB, `LATERAL`, arrays, or PostgreSQL composite values
  part of the public query API.
- Do not reproduce every SQL feature or dialect extension in the first
  implementation.
- Do not define a public multi-statement prefetch API in this RFC.
- Do not make arbitrary Kotlin LOAD privacy SQL-translatable.
- Do not guarantee ordering when the caller's SQL shape does not define a
  total order.
- Do not infer or silently add primary-key ordering.
- Do not make a statement-count claim about transaction-control commands,
  connection-pool validation, network fetch round trips, server triggers, or
  database-internal work.

## Core Invariant

### Query-Owned Work

For an executable query terminal:

1. EntKt captures the query and privacy context.
2. EntKt runs the root and configured relationship interceptors while
   compiling one immutable relational plan.
3. EntKt validates identifiers, capabilities, graph shape, and bind limits.
4. If compilation succeeds and the shape requires storage, EntKt submits the
   plan once to the driver.
5. A SQL driver renders and attempts exactly one top-level SQL command through
   one statement invocation.
6. EntKt decodes the result and applies LOAD privacy.
7. EntKt returns a `ReadResult`.

Static shape failure, interceptor rejection, or capability failure completes
with zero statements for the represented query. On row-materializing and
raw-exists shapes that honor caller bounds, root `limit(0)` retains its ordinary
SQL shape and executes one `SELECT ... LIMIT 0`. Count and aggregate terminals
retain their documented bound-ignoring semantics and still execute their one
aggregate statement. Relationship-local `limit(0)` remains part of the same
entity statement. No path can perform two statements for one ordinary query
terminal.

### Callback-Owned Work

Privacy rules and callbacks explicitly given a read client are trusted
application code. If such a callback calls that client, the nested read has its
own query plan and statement boundary:

```kotlin
batchPrivacyRule { context, batch ->
    val owners = context.client.users.query { /* ... */ }
        .all()
        .getOrThrow()
    batch.decideEach { item -> /* ... */ }
}
```

Tracing must attribute the nested query to the callback. It must not make the
original query's explain plan appear to contain that statement.

### Prohibited Hidden Work

A conforming query implementation must not hide any of the following beneath
one relational plan:

- one statement per relationship;
- parameter-list chunking into several statements;
- junction discovery followed by a separate target statement;
- a temporary-table creation or population prelude;
- a capability fallback that performs several statements;
- transparent framework or driver retries.

One statement invocation containing several top-level SQL commands is also
non-conforming. The renderer emits one parsed command, not a semicolon-joined
script.

When a driver cannot lower a plan as one statement, it reports an explicit
capability or query-shape error before statement execution. Applications can
then change the query or choose an explicitly multi-statement operation.

## Public Query Contract

The generated entity query remains the primary API:

```kotlin
client.users.query {
    where(User.active eq true)
    orderBy(User.createdAt.desc())
    limit(20)

    withPosts {
        where(Post.published eq true)
        orderBy(Post.createdAt.desc())
        limit(5)
    }
}.all()
```

The query block describes one relational result shape. `withPosts` adds a
nested relationship projection; it does not schedule a later fetch.

The guarantee applies equally to nested graphs:

```kotlin
client.users.query {
    withPosts {
        withComments {
            withAuthor()
        }
    }
    withRoles()
}.all()
```

The compiler produces one statement containing the complete requested graph.

### `with` Versus Relational `join`

These concepts have different result semantics:

- `with{Edge}` preserves the root entity result and fills an `EdgeState`.
- A future explicit `join` represents ordinary relational row composition and
  may multiply rows.

For example, joining users to posts can produce several projection rows for
one user. `withPosts`, by contrast, returns one logical root user whose
`edges.posts` is one loaded collection.

EntKt must not silently reinterpret an explicit fan-out join as a nested entity
graph, and it must not let `withPosts` unexpectedly multiply the public root
list.

### Configuration Order

Configured relationship paths are compiled and later privacy-evaluated
depth-first in the order their `with{Edge}` calls execute. A nested path is
completed before the next sibling path.

Configuring the same edge twice on one query node is an error rather than a
last-write-wins replacement. This avoids a result whose predicates, returned
`EagerLoad` handle, or callback order depend on an overwritten configuration.
The second call throws `EntQueryConfigurationException` immediately and leaves
the first configuration and its handle unchanged. If callers need two
differently filtered projections of the same relationship, that requires a
future explicitly named/aliased relationship-projection API.

## Relational Statement Model

The long-term driver boundary should accept one immutable relational plan
rather than a table name plus parallel terminal-specific arguments.

An illustrative internal shape is:

```kotlin
data class SelectQuery<R>(
    val root: RelationRef,
    val projection: Projection<R>,
    val joins: List<Join>,
    val predicate: SqlPredicate?,
    val groupBy: List<SqlExpression<*>>,
    val having: SqlPredicate?,
    val orderBy: List<SqlOrder>,
    val limit: Int?,
    val offset: Int?,
)

sealed interface Projection<out R> {
    class Entity(val row: EntityProjection) : Projection<EntityRow>
    class Scalar<T>(val expression: SqlExpression<T>) : Projection<T>
    class NestedOne<T>(val query: SelectQuery<T>) : Projection<T?>
    class NestedMany<T>(val query: SelectQuery<T>) : Projection<List<T>>
}
```

This example defines responsibilities, not final public classes. The actual
model also needs:

- relation and alias identity;
- relation-bound typed column references;
- typed expressions and bind values;
- nested and correlated subqueries;
- projection aliases or stable typed slots;
- `DISTINCT` and aggregate expressions;
- join type and `ON` predicates;
- decoder metadata;
- source attribution for caller, framework, and interceptor clauses.

Generated edge metadata is lowered above the SQL renderer. A driver should not
receive a predicate that says only `HasEdge("posts")` and then reconstruct
entity semantics itself. It receives the corresponding relational `EXISTS`,
join, or nested projection.

Every plan or decoder type referenced across runtime, driver, and generated
application modules must be public and guarded by `@EntktInternal`; Kotlin
`internal` is not visible across those module boundaries.

## Root Selection Before Graph Expansion

Root predicates, ordering, offset, and limit select root entities before any
to-many relationship can expand or aggregate them.

Conceptually, the compiler retains an internal root ordinal so graph expansion
cannot erase the authored root order:

```sql
WITH selected_users AS (
    SELECT
        users.*,
        ROW_NUMBER() OVER (ORDER BY users.created_at DESC) AS "__entkt_root_order_0"
    FROM users
    WHERE users.active = true
    ORDER BY users.created_at DESC
    LIMIT 20
)
SELECT /* root columns and nested projections */
FROM selected_users
/* relationship projections */
ORDER BY selected_users."__entkt_root_order_0"
```

Equivalent derived-table or lateral lowerings are valid. For a simple authored
column order, a renderer can repeat that order in the outer query instead. For
an expression order, it can project collision-proof hidden sort slots. What
matters is both the relational order of operations and an outermost order that
preserves the public root sequence.

`firstOrNull()` selects at most one logical root and then materializes every
requested relationship for that root. A join may not consume the one-row
window with one of several child rows.

## Relationship Lowering

### To-One Relationships

`belongsTo` and `hasOne` projections preserve the root even when:

- the foreign key is null;
- the referenced row is absent;
- target predicates exclude the row;
- a target interceptor excludes the row;
- `limit(0)` or a positive offset excludes the row.

An ordinary aliased `LEFT JOIN` is usually the simplest lowering. A correlated
or lateral projection is also valid when target ordering/bounds or nested
children require it.

The decoder uses an explicit target-presence marker. It must not infer absence
from a nullable target field. No selected target becomes
`EdgeState.Loaded(null)`; a selected target becomes `Loaded(entity)`.

### To-Many Relationships

`hasMany` projects one ordered nested rowset per root. Target predicates run
inside the nested relation. Per-parent ordering, offset, and limit are applied
inside that relation before its descendants are expanded.

For PostgreSQL, the expected initial family of lowerings is a correlated or
`LEFT JOIN LATERAL` child query followed by an ordered nested aggregate. The
public contract is the nested rowset, not that aggregate's encoding.

A driver may use flat rows and application-side folding only when it can avoid
Cartesian multiplication between independent collection paths and preserve
the same ordering, pagination, presence, and type semantics. Flat sibling
joins that produce `posts × roles × comments` are not conforming for
`with{Edge}`. A future explicit fan-out `join` remains the opt-in relational
row API.

### Many-To-Many Relationships

Many-to-many projection joins the junction and target relations inside the
same nested relationship expression:

```text
root
└── nested collection
    └── junction JOIN target
```

The lowering must:

1. correlate junction rows to the current source;
2. skip null endpoints;
3. collapse duplicate `(source ID, target ID)` memberships;
4. apply target predicates;
5. order eligible targets;
6. apply the per-parent offset and limit;
7. project the target and requested descendants.

Generated many-to-many entity edges have set membership. Bag/multiset
relationship semantics require a future distinct API rather than silently
changing this deduplication rule. No separate junction-discovery or
target-fetch statement is permitted.

### Nested Relationships

Every selected child row becomes the root of its configured nested
relationship projection. The compiler applies the same rules recursively:

- select and window the child rowset;
- project each requested to-one child;
- project each requested to-many child independently;
- avoid sibling collection cross products;
- preserve path-scoped aliases and decoder metadata.

A driver may lower a deep graph through correlated subqueries, lateral joins,
set-based CTE aggregation, or another equivalent one-statement plan. The
choice must be deterministic from the frozen plan and documented driver
capabilities, never selected through an invisible multi-statement fallback.

## Ordering And Per-Parent Windows

Root ordering controls root order. Edge ordering controls the corresponding
loaded collection order.

EntKt does not add an implicit primary-key term. Without `orderBy`, SQL row
order is unspecified. If authored ordering contains ties, tied-row order and
finite-window membership remain unspecified unless the caller supplies a
tie-breaker:

```kotlin
withPosts {
    orderBy(Post.createdAt.desc())
    orderBy(Post.id.asc())
    limit(5)
}
```

Explain should warn whenever total order cannot be proven statically, but it
must not silently rewrite the query. A sufficient proof is an authored order
ending in the non-null primary key or another non-null unique key. The warning
also applies to positive offsets without a finite limit and to the implicit
one-root window used by `firstOrNull()`. A structurally unique to-one
relationship needs no separate edge-order warning.

For a relationship node, the relational sequence is:

1. correlate the relationship;
2. apply caller and interceptor predicates;
3. deduplicate relationship membership where required;
4. apply ordering;
5. apply offset and limit per parent;
6. project nested descendants;
7. materialize and apply LOAD privacy.

Ordering must be carried by the nested collection value itself. A PostgreSQL
aggregate lowering cannot rely on an input subquery's incidental order; it
must render the required order where the aggregate contract preserves it.

Privacy-batch order is derived without relying on nested-transport encounter
accidents. EntKt walks parents in public result order, then each parent's
relationship list in its authored SQL order, and deduplicates target IDs at
first occurrence. The same rule applies recursively at deeper paths. That
first occurrence supplies the canonical decoded target copy for that logical
path and determines callback, first-denial, and exception order.

Offset/limit arithmetic must not overflow Kotlin `Int` or a backend expression.
`limit(0)` produces an empty to-many edge or null to-one edge without changing
the selected root set.

## Nested Result Transport And Decoding

The relational plan describes typed nested rows. It does not expose a wire
encoding.

A PostgreSQL driver may encode a collection with JSON/JSONB, arrays, composite
values, or another representation. That encoding must be schema-aware and
lossless. Applying `row_to_json(*)` and casting values heuristically is not a
sufficient contract.

Nested decoding must match ordinary row decoding for every supported field:

- nullable and non-null scalar values;
- enums;
- UUIDs;
- temporal values;
- byte arrays;
- typed JSON without accidental double encoding;
- native types such as pgvector;
- custom scalar codecs when supported.

The nested transport and codec dispatch must distinguish:

- SQL `NULL` field value;
- JSON `null` stored in a typed JSON field, even when the configured nullable
  codec ultimately maps it to the same Kotlin value as SQL `NULL`;
- absent to-one relationship;
- present to-one row whose selected fields include nulls;
- empty to-many relationship;
- unrequested relationship.

Projection slots and aliases are path-scoped and collision-proof. They must
support:

- self-referential edges;
- the same target entity reached through several edges;
- the same edge type configured differently at different paths;
- storage columns whose names resemble generated aliases;
- PostgreSQL's identifier-length limit.

Generated code remains responsible for materializing generated entity types.
The driver remains generated-entity-agnostic and returns typed internal rows or
slots described by the plan's decoder metadata.

The complete typed graph is decoded before LOAD privacy begins. Decoding walks
root rows and nested projection paths in the frozen plan order so failures are
deterministic and can carry a logical path. A nested transport or field-codec
failure can therefore precede root privacy. This RFC deliberately chooses
eager typed decoding over a staged/lazy decoder contract.

## `EdgeState` Semantics

The existing result model remains:

- no `with{Edge}` call: `EdgeState.Unloaded`;
- selected to-one with no row: `EdgeState.Loaded(null)`;
- selected to-one with a row: `EdgeState.Loaded(entity)`;
- selected to-many with no rows: `EdgeState.Loaded(emptyList())`;
- selected to-many with rows: `EdgeState.Loaded(list)`.

`with{Edge}` never changes root cardinality. A root appears once in the public
result regardless of how many related rows were materialized.

When a many-to-many target is shared by several roots, it remains attached to
each root. Privacy and nested materialization deduplicate that target once per
logical edge path, not globally across differently configured paths.

## Privacy

### Trusted Callback Boundary

LOAD privacy rules are trusted application code. The one relational statement
retrieves and EntKt decodes unverified root and nested data before rules run.

Applications must not log, transmit, persist, or otherwise disclose unverified
data from privacy callbacks. EntKt guarantees that denied data is not published
through a successful query result; it does not sandbox trusted policy code.

The RFC does not require nested payloads to remain opaque or lazily decoded
until authorization.

### Stable Privacy Inputs

An entity's LOAD privacy decision should not depend on which relationships the
caller happened to request. Root privacy items therefore contain root entities
whose edges are `Unloaded`. A target's privacy item likewise contains that
target with its own edges `Unloaded`.

The implementation has already decoded descendants internally, but they are
not attached to the entity value passed as that entity's privacy item.
Graph-aware authorization belongs in an explicit policy API rather than
appearing accidentally when `with{Edge}` changes a normal LOAD context.

### Evaluation Order

After the statement succeeds, EntKt evaluates authorization deterministically:

1. root LOAD privacy over the ordered root batch;
2. each configured edge path depth-first in `with{Edge}` configuration order;
3. one target batch per logical path, ordered by parent order then each
   parent's relationship order and deduplicated by ID at first occurrence;
4. descendants only for retained parent targets.

A shared target is evaluated once within one edge path and its canonical
authorized copy is reattached wherever that path references it. It is not
globally interned across different paths.

The denial path is rooted at the entity terminal and contains only configured
`with{Edge}` hops. It is intentionally different from an interceptor's
`QueryContext.path`, which also contains upstream `query{Edge}` traversal hops.
For example, a query traversed from `Organization` to `User` and then configured
with `User.posts` reports an eager denial path beginning at `User.posts`, while
the posts interceptor sees the complete `Organization -> User -> posts` path.

Strict root `all()` preserves the existing aggregate contract: it reports every
denied root key in selected-root order and runs no edge privacy after a root
denial. Strict eager mode reports the first denied target in the path batch as
`Failed(EntPrivacyDeniedException(EagerEdge(path), ...))`.
`visibleOrNull()` maps only root denial to absence and never maps eager denial.

`filterVisible()` removes denied targets only from that configured edge:

- denied to-one target: `Loaded(null)`;
- denied to-many target: omitted from the loaded collection.

Filtering happens after the SQL window and never scans for replacement rows.
It is not inherited by descendants and does not suppress rule-thrown
exceptions. Empty target batches invoke no LOAD rule.

### Physical Timing Change

The graph statement executes before arbitrary Kotlin LOAD privacy. Therefore:

- root denial does not imply that relationship storage work was avoided;
- parent or target denial does not imply that descendant storage work was
  avoided;
- a statement-level SQL failure can occur before any LOAD privacy callback;
- there is no physical per-edge query-failure order.

This is an intentional consequence of the one-statement contract. Query-time
visibility predicates may later exclude unauthorized rows inside SQL, but they
do not replace arbitrary Kotlin LOAD authorization.

One terminal-captured `PrivacyContext` is used throughout root and edge
privacy evaluation. Queries explicitly issued by a rule use their own normal
query lifecycle and are outside the original statement count.

## Read Interceptors

Every read interceptor contributing to the query runs before the represented
statement is submitted. Fixed API-shape validation happens first, so a
predictably invalid terminal/graph combination does not run callback side
effects.

The compiler then preserves the existing interceptor walk and extends it for
nested projections:

1. Resolve any deferred `query{Edge}` source steps from source to target, using
   `ReadOperation.EDGE_TRAVERSAL`.
2. Run the current/root terminal chain with its terminal-specific
   `ReadOperation`.
3. Walk post-interceptor predicates in deterministic expression encounter
   order. For every relationship predicate, run the target chain with
   `ReadOperation.EDGE_PREDICATE`, recursively including relationship
   predicates added by interceptors.
4. Compile configured `with{Edge}` projections depth-first in call order. At
   each path, run the target chain with `ReadOperation.EAGER_LOAD`, then walk
   that target's relationship predicates, then compile its nested projections.

Within every chain, ordering remains:

1. generated framework interceptors;
2. entity interceptors in registration order;
3. global interceptors in registration order.

`QueryContext` retains the exact root/current/source entity, edge name, typed
path, privacy context, public flags, and operation for that logical step. Flags
remain local to the block that configured the step; they do not inherit from a
parent query or relationship.

Existing limit-mutator semantics remain. Limit mutation is a silent no-op for
`EAGER_LOAD` and `EDGE_PREDICATE`; caller-authored per-parent bounds still
belong to the nested projection. It applies normally to the operations where
the implemented interceptor contract currently permits it.

An interceptor rejection or ordinary exception during compilation executes
zero statements for the represented query. An unsupported query performed by
capturing a client inside an interceptor remains outside the contract; no
statement-count or ordering guarantee is made for that misuse.

Current eager interceptors can observe a concrete structural
`targetColumn IN (materializedParentIds)` predicate because they run after the
root or junction query. Those IDs do not exist during one-statement
compilation. The new interceptor shape must expose symbolic relationship
correlation instead:

```text
path: User.posts
cardinality: TO_MANY
correlation: Post.authorId = User.id
target predicates: Post.published = true
per-parent order: Post.createdAt DESC
per-parent limit: 5
```

For many-to-many paths it includes the junction relation and both correlation
legs. It does not fabricate a concrete `IN` list.

Symbolic correlation moves to a new typed `QueryShape.relationship` field (and
an erased counterpart for global interceptors). It is not an ordinary
value-bearing `Predicate` and is excluded from `predicates` and
`structuralPredicateCount`. The existing attribution identity continues to
count only actual caller, structural-value, and interceptor predicates. This
is an intentional public `QueryShape` compatibility change.

Predicates added by a target interceptor are scoped to that path's target
alias and execute inside the nested target relation before ordering and
windowing. A target predicate must not leak into the outer `WHERE` clause and
accidentally remove the root, nor may a predicate on a left-joined to-one target
turn the root-preserving relationship into an inner join.

`has` and `with{Edge}` remain separate logical uses of a relationship and
receive separate interceptor operations when both are present. The first is a
filtering subexpression; the second is a nested result projection.

Configured relationship interceptors run once per path even when the eventual
root or target result is empty. This preserves query-shape policy and rejection
behavior without requiring data-dependent callback invocation.

### Many-To-Many Junction Policy

Junction rows used only to realize a generated many-to-many entity edge remain
internal association storage. As in the current eager path, querying a target
through `with{Edge}` does not run the through entity's LOAD privacy or read
interceptors. Target interceptors narrow the target relation; framework
correlation constrains the junction relation.

Explicitly querying or projecting the through entity uses that entity's normal
privacy and interceptor lifecycle. Applying independent through-row policy to
an edge projection requires a future edge/junction-policy API with an explicit
operation and path; it must not appear implicitly because the driver happened
to lower an edge through a registered entity table.

## Failure And Precedence Semantics

Failure precedence follows the actual one-statement lifecycle:

1. compilation, validation, and interceptor failures;
2. the single driver/SQL statement failure;
3. transport and typed graph decoding failures, traversed root-first and then
   by frozen projection path;
4. root privacy failure or denial;
5. edge privacy failure or denial in deterministic path order.

A SQL error inside a nested expression is a statement error. EntKt must not
claim that it came from an independently executed eager query or promise an
edge order that the database optimizer does not provide.

Decoder failures must carry the logical projection path after transport has
identified one; a failure parsing the statement-wide outer transport may be
reported at the root result. Privacy denials retain their existing root or
eager-edge origin.

The canonical read exception boundary remains unchanged across every stage:

- `CancellationException` propagates;
- JVM `Error` values propagate because the boundary never catches `Throwable`;
- every other thrown `Exception` is stored unchanged in `ReadResult.Failed`;
- read execution never invokes driver exception classification.

This applies uniformly to compiler/interceptor, driver, transport, decoder,
codec, and privacy failures. The lifecycle ordering above determines which
ordinary exception reaches the boundary first; it does not wrap or reclassify
that exception.

## Terminal Families

Entity-materializing terminals such as `all()` and `firstOrNull()` include
configured `with{Edge}` projections in their one statement.

Terminals that do not materialize entities must not silently ignore configured
edges. Until a projection explicitly defines relationship values, EntKt should
reject graph configuration on:

- count and exists terminals;
- scalar or grouped aggregate terminals;
- a source query converted into a traversal whose source graph would not be
  returned;
- future projections that do not select the relationship.

This rejection occurs before storage I/O and names the incompatible terminal
and configured path.

The error boundary is explicit:

- duplicate `with{Edge}` configuration throws
  `EntQueryConfigurationException` at the second DSL call;
- converting a graph-bearing source with `query{Edge}` throws the same
  exception at conversion because there is no result-bearing terminal yet;
- invoking an incompatible result-bearing terminal returns
  `ReadResult.Failed(EntQueryConfigurationException)`;
- unsupported driver capability returns the driver's canonical operational
  failure in `ReadResult.Failed`;
- none of these failures mutates the reusable query builder.

Explain represents an incompatible terminal or unsupported capability as a
non-executable plan with the exact configuration/capability problem and no
driver subplan. An interceptor `scope.reject(...)` retains the current rejected
plan representation. Non-reject interceptor exceptions still propagate
unchanged.

Future typed projections, explicit joins, grouping, and `HAVING` should compile
through the same relational plan. Their privacy posture and Kotlin result
types remain separate RFC decisions; they may not be implemented through
hidden hydration queries.

Repository `findById`, indexed helper terminals, root and transaction-scoped
queries, raw count/exists, and every scalar/grouped aggregate remain within the
same one-statement architecture. Terminal-specific semantics remain explicit:

- entity terminals can materialize configured relationships;
- count and aggregate shapes retain their documented treatment of ordering and
  bounds;
- raw exists retains its documented offset and `limit(0)` behavior while
  dropping irrelevant ordering;
- low-level by-ID operations used inside mutation implementations may remain a
  narrow driver primitive, but public repo reads lower through the relational
  plan so interceptor predicates are not lost;
- every explain mirror compiles the same relational shape as its terminal.

## Driver Contract

The target read-side driver boundary is conceptually:

```kotlin
interface RelationalQueryDriver {
    fun executeSelect(query: SelectQuery<*>): RelationalResult
    fun explainSelect(query: SelectQuery<*>): QueryExplanation
}
```

The exact type names remain open. The contract does not:

- expose generated entity classes to drivers;
- expose public JSON transport details;
- require every driver to support every expression or nested projection;
- allow one call to execute several statements.

A conforming SQL implementation performs one statement attempt for
`executeSelect`. Result-set fetches may involve several protocol round trips;
those are not additional SQL statements.

### Capability Preflight

Before submitting the represented statement, and without issuing helper SQL,
the compiler/driver validates everything knowable from the frozen plan:

- relation and column identifiers;
- expression and aggregate support;
- nested-object and nested-collection projection support;
- required storage codecs;
- bind count or alternate one-statement bind lowering;
- known static query-depth, projection-width, SQL-size, column-count, and
  expression-size limits.

If a user-supplied `IN` collection is too large, the driver may lower it to one
array/table-valued parameter inside the statement. Otherwise it fails before
execution with the backend limit and logical query path. It may not split the
collection into several statements.

Drivers without nested relational projection support reject `with{Edge}`
queries before execution. They do not inherit a default per-edge loop.

A transaction-scoped driver may already own an open connection, and some
capability metadata may have been discovered during driver initialization.
The guarantee is zero auxiliary SQL and no submission of the represented
statement before rejection, not that no connection object exists.

Actual nested payload size and result cardinality are data-dependent and
cannot be preflighted without the forbidden extra read. Unbounded collection
projections receive an explain warning; backend or decoder resource failures
during the one statement retain the canonical read exception behavior described
above.

### Transaction And Snapshot Behavior

The graph is read by one statement and therefore one database command snapshot
under databases such as PostgreSQL. An explicit surrounding transaction still
controls the broader isolation and lifetime.

Transaction-control commands are not part of the query statement count.
Callback-issued reads are separate statements and may observe a later snapshot
under `READ COMMITTED`.

## PostgreSQL Direction

The initial PostgreSQL implementation should favor:

- aliased root-preserving joins for simple to-one relationships;
- correlated or `LEFT JOIN LATERAL` projections for target-specific bounds;
- independent ordered aggregates for sibling to-many relationships;
- junction-to-target nested projections for many-to-many relationships;
- bounded root subqueries or CTEs before graph expansion;
- schema-aware nested encoding and decoding.

For a conceptual to-many edge:

```sql
SELECT
    selected_users.*,
    COALESCE(
        (
            SELECT jsonb_agg(
                jsonb_build_array(p.id, p.title, p.author_id, p.created_at)
                ORDER BY p.created_at DESC
            )
            FROM (
                SELECT posts.id, posts.title, posts.author_id, posts.created_at
                FROM posts
                WHERE posts.author_id = selected_users.id
                  AND posts.published = TRUE
                ORDER BY posts.created_at DESC
                LIMIT 5
            ) AS p
        ),
        jsonb_build_array()
    ) AS "__entkt_posts_0"
FROM (
    SELECT
        users.*,
        ROW_NUMBER() OVER (ORDER BY users.created_at DESC) AS "__entkt_root_order_0"
    FROM users
    WHERE users.active = TRUE
    ORDER BY users.created_at DESC
    LIMIT 20
) AS selected_users
ORDER BY selected_users."__entkt_root_order_0"
```

This SQL is illustrative. The implementation may prefer `LATERAL`, arrays,
positional values, set-based CTE aggregation, or another plan that satisfies
the same semantics and type fidelity.

One statement does not automatically mean one database subplan execution per
edge. A naive correlated plan may still execute substantial work per root.
The PostgreSQL implementation must emit index-eligible correlations,
root/window pushdown, and non-Cartesian collection lowering; the optimizer,
not EntKt, ultimately decides whether to use an index. Dry-run explain makes
the actual EntKt relational lowering and rendered SQL visible. This RFC
promises a statement boundary and result semantics, not that every accepted
query is inexpensive.

## Explain And Observability

Explain output describes one complete relational statement, not a root plan
plus a tree of later eager statements.

It should report:

- the rendered SQL shape;
- redacted binds by default;
- root projection and decoder;
- every nested relationship path;
- relationship cardinality and correlation;
- target predicates and their attribution;
- per-parent ordering and bounds;
- the chosen physical lowering, such as joined object or lateral aggregate;
- capability requirements and preflight warnings;
- whether LOAD privacy runs after materialization;
- unstable-order warnings for bounded unordered or tied shapes.

Authored, framework-correlation, and interceptor-added clauses must remain
separately attributable. Application annotations cannot overwrite
framework-owned strategy metadata.

An explain call compiles a point-in-time frozen plan and executes no SQL. If the
public API later exposes a compiled query handle, explaining and executing that
same handle must use the exact same plan:

```kotlin
val compiled = query.compileAll()
compiled.explain()
compiled.execute()
```

Without such a handle, a later terminal call recompiles and may observe changed
callback or captured state; explain must not claim otherwise.

Dry-run explain shows EntKt's relational lowering and rendered SQL, not the
database optimizer's execution plan. A future explicitly named database
diagnostic such as `explainAnalyze()` may submit `EXPLAIN` as its own SQL
statement; it is not an ordinary data-query terminal and must document whether
it executes the underlying query.

For a terminal that honors caller bounds, root `limit(0)` explain still shows
the `LIMIT 0` statement. Count and aggregate explain mirrors show the same
bound-ignoring relational shape as their terminals. Interceptor rejection
returns the existing rejected plan with no driver subplan; non-reject
interceptor exceptions propagate. Static configuration or capability failure
returns the non-executable problem described under Terminal Families.

When the separate query-observability feature lands, runtime tracing should
record the represented query statement and separately nest queries explicitly
initiated by privacy rules or other supported callbacks. Tracing is not a
prerequisite for this RFC; preserving independent compiled-plan boundaries is.

## Reference Comparison

This direction uses established systems as comparative input without copying
their full APIs.

- [jOOQ `MULTISET`](https://www.jooq.org/doc/latest/manual/sql-building/column-expressions/multiset-value-constructor/)
  is the closest logical precedent for projecting a correlated nested rowset
  while keeping JSON/XML emulation internal. EntKt does not copy its undefined
  element-ordinal contract: an authored edge order must be preserved in the
  loaded Kotlin list.
- [Drizzle relational queries](https://orm.drizzle.team/docs/data-querying) document an
  exactly-one-query nested result contract and use lateral/JSON lowering on
  PostgreSQL. EntKt additionally needs `EdgeState`, LOAD privacy, and generated
  lifecycle semantics.
- [PostgreSQL `LATERAL`](https://www.postgresql.org/docs/current/queries-table-expressions.html#QUERIES-LATERAL)
  provides the correlation mechanism. PostgreSQL
  [aggregate ordering and empty-input semantics](https://www.postgresql.org/docs/current/functions-aggregate.html)
  require an explicit aggregate order and empty-collection representation.
- [EF Core single versus split queries](https://learn.microsoft.com/en-us/ef/core/querying/single-split-queries)
  demonstrates why flat sibling collection joins cause Cartesian explosion.
- Django limits
  [`select_related`](https://docs.djangoproject.com/en/stable/ref/models/querysets/#select-related)
  to single-valued relationships and uses separate prefetch operations for
  collections. EntKt instead keeps collection `with{Edge}` one-statement and
  requires a non-Cartesian nested projection.
- [Rails eager-loading APIs](https://guides.rubyonrails.org/active_record_querying.html#eager-loading-associations)
  can switch strategy between preload and join behavior based on the selected
  API and query details. EntKt should not copy a strategy-switching surface
  whose statement count changes under the same relationship-selection syntax.
- [EntGo `With{Edge}`](https://entgo.io/docs/eager-load/#implementation) uses
  additional queries. EntKt retains similar generated ergonomics but gives
  `with{Edge}` a different, fixed statement contract.

No public `relationLoadStrategy` option is proposed. A query keeps the same
statement count across supported drivers; unsupported shapes fail explicitly.

## Compatibility And Supersession

This RFC supersedes the default execution direction in
[Set-Based Eager Graph Loader](set-based-eager-graph-loader.md).

The following result requirements remain compatibility inputs:

- explicit `EdgeState` distinctions;
- root-cardinality preservation;
- per-parent relationship predicates and windows;
- strict eager privacy and edge-local `filterVisible()`;
- shared-target attachment and path-specific privacy;
- typed interceptor paths.

The following parts are replaced:

- a root statement followed by per-edge statements;
- a graph executor whose physical query count grows with edge paths/chunks;
- concrete parent-ID `IN` shapes for eager interceptors;
- separate junction and target reads;
- `RelatedQuery` / `queryRelated` driver operations;
- physical depth-first eager-query failure precedence;
- parameter chunking beneath one logical edge step.

The cutover also makes these intentional observable changes to current
behavior:

- sibling `with{Edge}` paths use DSL call order rather than schema declaration
  order;
- repeated same-edge configuration fails instead of replacing the prior shape
  and invalidating its handle;
- all relationship interceptors run before the statement rather than after
  root/junction materialization;
- relationship interceptor shape carries symbolic correlation rather than a
  concrete parent-ID `IN` predicate, changing `QueryShape` attribution;
- the statement can perform relationship work before root LOAD privacy;
- grouped nested privacy/interceptor callbacks run once per logical path rather
  than once per populated parent group;
- graph configuration on non-materializing terminals rejects instead of being
  silently ignored;
- SQL/decoder failure precedence is statement/graph-wide rather than based on
  separately executed edge queries.

The current canonical result behavior remains: strict root `all()` aggregates
all denied root keys in selected order, while strict eager privacy reports the
first denied target for its path. The superseded loader RFC's proposed implicit
primary-key ordering is not adopted; EntKt keeps ordinary authored SQL order.

Other query proposals must treat this RFC as the statement-boundary owner:

- projections, aggregates, cursor pagination, and SQL expressions lower into
  one relational plan;
- a visible-page scan that performs several reads is an explicitly named
  orchestration utility, not an ordinary query terminal;
- request-scoped loaders and a possible prefetch API remain outside the core;
- `forUpdate()` must identify the root relation so nested projections are not
  accidentally locked;
- query-time visibility predicates apply inside the relevant root or nested
  relation before ordering and bounds;
- modular-driver and thin-runtime-engine proposals use this plan rather than a
  separate eager execution SPI.

Replacing the read-side driver SPI is source-breaking for custom drivers.
Non-SQL drivers must implement equivalent relational semantics for accepted
plans or reject unsupported shapes before I/O. The implementation must provide
a custom-driver compilation/conformance fixture and carry current query flags,
annotations, operation metadata, and bind-snapshot guarantees into the new
plan.

## Migration Strategy

The implementation should land in reviewable phases while preserving the final
one-statement rule at every public cutover.

### Phase 1: Relational Plan Foundation

1. Add the immutable relation, expression, projection, alias, and decoder model.
2. Lower existing root predicates, `has`, shaped traversal, ordering, and
   bounds into it.
3. Preserve semantic snapshots of mutable caller and interceptor bind operands
   when freezing the plan.
4. Add one driver execute/explain entry and PostgreSQL renderer.
5. Move public `all()`, `firstOrNull()`, repo `findById`, indexed helpers,
   raw count/exists, every scalar/grouped aggregate, transaction-scoped reads,
   and all explain mirrors to the plan without changing their terminal-specific
   semantics.
6. Retain narrow legacy by-ID reads only where mutation internals require them.
7. Add SQL and non-SQL custom-driver conformance fixtures plus one-statement
   counting tests.

### Phase 2: To-One Projection

1. Add nested-object projection metadata and presence markers.
2. Compile `belongsTo`, `hasOne`, filtering, bounds, and nested to-one paths.
3. Compile eager interceptors through symbolic relationship correlation.
4. Preserve `EdgeState` and target privacy behavior in internal probes.

### Phase 3: Collection Projection

1. Add typed nested-collection transport and decoder support.
2. Implement direct to-many per-parent ordering and bounds.
3. Implement many-to-many junction/target projection and pair deduplication.
4. Add independent sibling collection lowering with no Cartesian product.
5. Implement arbitrary nested paths.

Phases 2 and 3 remain internal and unadvertised. Public `with{Edge}` continues
to use the clearly documented current implementation until the atomic cutover
below. No release may mix one-statement to-one loading with multi-statement
collection loading under the same `with` contract.

### Phase 4: Atomic Lifecycle And Diagnostics Cutover

1. Run all interceptors before the statement in documented path order.
2. Apply root and path privacy after result materialization.
3. Preserve strict denial and `filterVisible()` results.
4. Replace graph-shaped query-count explain output with one-statement output.
5. Remove generated `loadEdges` and separate eager driver calls.
6. Update public query, privacy, driver, lifecycle, and breaking-change docs.

At cutover, every generated `with{Edge}` shape either compiles into the one
statement or rejects before I/O. No unconverted shape invokes the old loader.

No phase should silently choose the old multi-statement strategy for a query
advertising the new contract. Before the full cutover, the old implementation
remains clearly documented as current behavior.

## Test Requirements

### Statement Boundary

- root-only `all()`, `firstOrNull()`, public `findById`, indexed helpers,
  raw count/exists, every aggregate family, and transaction-scoped equivalents
  execute one represented-query statement;
- each to-one, to-many, and many-to-many shape executes one statement;
- multiple sibling collections and depth-three nested graphs still execute one
  statement;
- traversal plus nested projection executes one statement;
- root `limit(0)` on row/existence terminals that honor bounds executes its one
  `SELECT ... LIMIT 0` statement, while count/aggregate terminals ignore it and
  execute their one aggregate statement;
- no temp-table, junction-helper, chunk, or fallback statement occurs;
- rendered SQL contains one top-level command rather than a multi-command
  string hidden behind one JDBC invocation;
- interceptor, capability, bind-limit, and compilation rejection execute zero
  represented-query statements;
- a callback-issued query retains an independent compiled-plan boundary for
  future tracing.

### SQL Shape And Results

- root ordering and bounds select roots before graph expansion, and the
  authored order is reapplied at the outermost query;
- root order and cardinality do not change when edges are requested;
- `firstOrNull()` with a collection still selects one logical root rather than
  one flattened child row;
- to-one filtering is root-preserving;
- a required belongs-to target excluded by a predicate/interceptor is
  `Loaded(null)` rather than removing its root;
- to-one positive offset, edge `limit(0)`, nested `limit(0)`, and root
  `limit(0)` on bound-honoring terminals preserve their documented states and
  interceptor behavior;
- sibling to-many paths do not form a Cartesian product;
- each edge offset and limit is per parent;
- nested children expand only the selected parent window;
- M2M junction and target work appears inside the same statement;
- duplicate M2M pairs collapse before windowing and null endpoints are skipped;
- symmetric/self-referential M2M paths preserve source/target orientation;
- explicit edge ordering is preserved inside the nested result;
- unordered shapes do not gain an implicit primary-key clause;
- relationship `limit(0)`, empty collections, missing to-one rows, and absent
  roots preserve their exact `EdgeState`/result meanings.

### Privacy And Interceptors

- root and target privacy inputs have edges `Unloaded` regardless of requested
  graph shape;
- root privacy runs before edge privacy after statement execution;
- strict root `all()` reports every denied root key in selected order and stops
  before edge privacy;
- strict eager privacy reports the first denied target for the complete path;
- `visibleOrNull()` maps only root denial and never eager denial;
- configured paths run depth-first in `with{Edge}` call order;
- one target batch ordered by parent order then relationship order and
  deduplicated at first target-ID occurrence is evaluated per edge path;
- a shared M2M target with descendants remains attached everywhere, uses the
  first-occurrence canonical copy, and is evaluated once per path;
- strict denial retains the complete path;
- traversal followed by eager denial keeps the full traversal prefix in
  interceptor `QueryContext.path` but excludes it from the terminal-rooted
  `LoadDenialOrigin.EagerEdge.path`;
- `filterVisible()` is local, is not inherited, never replacement-scans, and
  never suppresses a rule-thrown exception;
- empty target batches invoke no privacy callback and batch evaluator decision
  handling retains its existing contract;
- a denied parent skips descendant privacy evaluation without claiming the
  descendant SQL was skipped;
- one exact `PrivacyContext` is used throughout;
- relationship interceptors receive symbolic correlation, not fabricated IDs;
- interceptor predicates land inside the correct path before windowing;
- empty eventual paths still receive their one compile-time interceptor pass;
- deferred traversal, root/current, edge-predicate, and nested-projection
  interceptor chains run in the documented total order;
- every chain preserves `ReadOperation`, root/current/source/path, flags,
  annotations, framework/entity/global order, and limit-mutator semantics;
- target predicates on left-joined to-one paths remain in `ON` or the
  correlated target relation rather than the outer `WHERE`;
- through-entity interceptors/privacy do not run for internal M2M junction use,
  but do run when that entity is queried explicitly;
- duplicate `with{Edge}` configuration fails clearly and does not mutate the
  first configuration;
- mutable authored and interceptor-added bind operands are semantically
  snapshotted when the relational plan freezes.

### Type And Codec Fidelity

- every field type decodes identically at root and at every nested level;
- nullable scalars, enums, UUID/time, bytes, typed JSON, and native pgvector
  have direct regressions;
- codec dispatch preserves SQL-null versus typed-JSON-null provenance even
  when a nullable codec maps them to the same Kotlin value;
- absent to-one and empty to-many remain distinct from present values;
- self-edges and repeated target types use distinct path-scoped slots;
- generated aliases cannot collide with storage names or each other;
- long generated paths respect backend identifier limits;
- mutable decoded values preserve existing defensive-copy guarantees;
- a corrupt later-edge codec fails before root privacy, consistently with the
  eager typed-decoding decision, and reports its logical projection path;
- outer transport parse failure is distinguishable from a path-scoped field
  decode failure.

### Capabilities And Errors

- an unsupported driver rejects nested projections before I/O;
- oversized binds use a one-statement lowering or reject before I/O;
- excessive graph depth/width fails explicitly when a driver declares a limit;
- data-dependent nested payload/resource failures remain execution failures
  rather than claiming impossible preflight;
- SQL failures are reported as statement failures, not fictional per-edge
  query failures;
- cancellation and JVM errors propagate from compiler/interceptor, driver,
  decoder/codec, and privacy stages;
- every other thrown read exception is stored unchanged in `ReadResult.Failed`,
  and driver exception classification is never invoked;
- duplicate-edge, incompatible-terminal, traversal-conversion, and unsupported
  capability failures use the documented throw/result/explain boundaries;
- decoder failures identify the logical projection path after the outer
  transport identifies one;
- explain shows one statement, redacted binds, attribution, edge windows,
  selected lowering, and statically provable instability/capability warnings;
- rejected and non-executable explain plans contain no driver subplan, while
  non-reject interceptor exceptions propagate;
- database optimizer `EXPLAIN` is not confused with zero-SQL dry-run explain.

## Resolved Decisions

- An ordinary query owns at most one represented-query relational statement.
- Reads issued by callbacks explicitly given a read client are allowed and
  retain separate query-plan boundaries.
- `with{Edge}` remains query syntax and participates in that statement.
- To-one relationships may use ordinary root-preserving joins.
- To-many and many-to-many relationships use non-Cartesian nested relational
  projections.
- The nested wire encoding is driver-internal.
- No hidden multi-statement fallback or retry is permitted.
- Root and edge bounds retain ordinary SQL and per-parent semantics.
- EntKt does not add hidden ordering.
- Privacy code is trusted; the framework does not require opaque or delayed
  nested decoding.
- Complete typed graph decoding precedes LOAD privacy.
- Privacy inputs remain graph-shape-independent with edges `Unloaded`.
- Interceptors compile symbolic relationship constraints before execution.
- SQL errors have statement-level rather than per-edge physical precedence.

## Open Decisions

- The exact immutable relation/projection/decoder type hierarchy.
- PostgreSQL's initial nested transport: positional JSON/JSONB, arrays,
  composites, or another schema-aware encoding.
- Correlated/LATERAL lowering versus normalized set-based CTE aggregation for
  large graphs.
- Whether a public compiled-query handle is worth exposing.
- Exact static preflight limits for graph depth, projection width, SQL size,
  and expression complexity.
- Whether an explicitly named multi-statement prefetch utility should be
  proposed separately.
- The final public API and privacy posture for arbitrary relational
  projections and explicit joins.

## Related Features

- [Loaded Edge State](../../implemented-features/query/loaded-edge-state.md)
- [Shape-Preserving Edge Traversal](../../implemented-features/query/edge-traversal-source-shape.md)
- [Read-Path Interceptors](../../implemented-features/query/read-path-interceptors.md)
- [Canonical Operation Result Algebra](../../implemented-features/api/operation-result-algebra.md)
- [Projection / Select API](projection-select-api.md)
- [Query Observability Diagnostics](query-observability-diagnostics.md)
- [Explicit Query Authority And Cost](explicit-query-authority-and-cost.md)
- [Query-Time Visibility Predicates](../privacy-validation/query-time-visibility-predicates.md)
- [Modular Driver SPI](../tooling/modular-driver-spi.md)
- [Driver Capability Matrix](../tooling/driver-capability-matrix.md)
- [Thin Codegen And Runtime Execution Engines](../tooling/thin-codegen-runtime-engines.md)
- [Set-Based Eager Graph Loader](set-based-eager-graph-loader.md) (superseded
  execution direction)
