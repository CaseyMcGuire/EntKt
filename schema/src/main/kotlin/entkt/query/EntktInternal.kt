package entkt.query

/**
 * Opt-in marker for framework-internal entkt API: surfaces that are
 * wired by generated code and are unsupported for application use.
 *
 * `internal` visibility cannot express this boundary: generated code
 * compiles into the consuming application's module, so anything
 * `internal` that generated code can reach, same-module application
 * code can reach too — and conversely, `internal` declarations in
 * `:schema` / `:runtime` would be unreachable from the generated
 * module. `@RequiresOptIn` is the mechanism that works across that
 * layout: generated `.kt` files declare
 * `@file:OptIn(EntktInternal::class)` at the file header, while
 * application code touching a marked declaration gets a hard compile
 * error until it opts in explicitly — a deliberate, greppable act, not
 * a security boundary.
 *
 * Guarded surfaces and the invariant each protects:
 *
 * - **Edge-predicate constructors** ([Predicate.HasEdge],
 *   [Predicate.HasEdgeWith], [Predicate.HasM2MEdgeFrom], [EdgeRef]):
 *   the edge-predicate walker emits an unchecked cast
 *   (`predicate.inner as Predicate<Target>`) keyed on the edge-name
 *   string, sound only when these types are built by the typed
 *   `EdgeRef.has { ... }` / `EdgeRef.exists()` surface. (Predicate.Leaf,
 *   Column subclasses, and OrderField constructors stay public: their
 *   residual hole surfaces at driver-render time and does not gate
 *   walker-cast soundness.)
 * - **Shaped traversal bridge constructors**
 *   ([Predicate.HasEdgeFromShape], [Predicate.HasM2MEdgeFromShape]):
 *   the edge-name/table pairing against the embedded
 *   [TraversalSourceShape]'s entity scope is established by generated
 *   `queryX()` traversal code from the schema; a hand-built mismatch
 *   would lower into a subquery against the wrong table or column.
 * - **The raw interceptor registry** (`EntInterceptorsConfig` access via
 *   `entityInterceptors`, `addEntity`): registration is scope-key-keyed
 *   with an unchecked cast, so untyped access could bind a
 *   wrong-entity interceptor to another repo's scope.
 * - **Query traversal seeders** (`snapshotForTraversal`,
 *   `seedEdgeTraversal`, `setDeferredSourceStep`): spoofed traversal
 *   context would corrupt the interceptor QueryContext view and the
 *   traversal bridge.
 * - **The read-runtime contract** (`EntReadRuntime`, per-entity
 *   `${Entity}ReadSurface`): the seam between generated queries and
 *   their host clients — implementing or invoking it directly is
 *   framework wiring, not application API.
 * - **Read-client construction** (`ReadOnlyEntClientImpl` / `${Entity}ReadRepo`
 *   constructors): mints the stable contextless readers owned by a generated
 *   client; generated client initialization is the supported construction
 *   path.
 *
 * If extension code genuinely needs one of these, add
 * `@OptIn(EntktInternal::class)` (or `@file:OptIn`) and own the
 * invariants above.
 */
@RequiresOptIn(
    message = "Framework-internal entkt API: this surface is wired by generated code " +
        "and is unsupported for application use. Its soundness invariants are " +
        "documented on entkt.query.EntktInternal. If extension code must use it, " +
        "add @OptIn(EntktInternal::class) (or @file:OptIn) and own those invariants.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class EntktInternal
