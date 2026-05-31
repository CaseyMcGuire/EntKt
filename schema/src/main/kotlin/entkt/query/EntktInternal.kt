package entkt.query

/**
 * Opt-in marker for entkt-internal construction sites that must not be
 * called from application code.
 *
 * The phantom-typed query scopes's [Edge-Predicate Walker] relies on
 * a small set of constructors being reachable only from generated codegen
 * output or from `:schema`'s own typed surface (`EdgeRef.has`,
 * `EdgeRef.exists`). The walker emits an unchecked cast
 * (`predicate.inner as Predicate<Target>`) inside a per-edge `when` arm,
 * with the edge-name string as the runtime witness — that cast is sound
 * only if `HasEdgeWith<E, Target>` cannot be fabricated outside the typed
 * `EdgeRef.has { ... }` call site.
 *
 * `internal` visibility cannot express this constraint across modules:
 * the generated query/entity code lives in the consuming application
 * module, not in `:schema`, so an `internal` constructor on `EdgeRef`
 * would be unreachable from generated code; and `internal` would also
 * stay visible to user application code in that same generated module.
 *
 * `@RequiresOptIn` propagates across module boundaries via
 * `Retention.BINARY`. Generated `.kt` files declare
 * `@file:OptIn(EntktInternal::class)` at the file header; application
 * code attempting to construct an annotated type raises a hard compile
 * error.
 *
 * Constructors carrying `@EntktInternal`:
 *  - [Predicate.HasEdge]
 *  - [Predicate.HasEdgeWith]
 *  - [Predicate.HasM2MEdgeFrom]
 *  - [EdgeRef]
 *
 * Predicate.Leaf, Column (and subclasses), and OrderField primary
 * constructors stay `public`: their residual hole
 * (wrong-column-name-within-the-right-entity) surfaces at driver-render
 * time and does not gate walker-cast soundness.
 */
@RequiresOptIn(
    message = "Internal entkt construction site; direct fabrication can bypass " +
        "edge-walker soundness. Construct edge predicates only via the generated " +
        "EdgeRef.has(...) / EdgeRef.exists() surface. If extension code requires " +
        "direct construction, add @file:OptIn(EntktInternal::class) at the file " +
        "header and own the soundness invariants.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@MustBeDocumented
annotation class EntktInternal
