package entkt.runtime.result

/**
 * Operations that framework-created typed exceptions describe. Mirrors
 * [entkt.runtime.privacy.PrivacyOperation] for the four CRUD operations
 * so name-based mapping is 1:1, and adds [QUERY] and [EDGE_MUTATION]
 * for surfaces that have no privacy-operation analog.
 *
 * A small shared metadata type, not a universal failure algebra. On
 * [EntMutationPrivacyDeniedException] it identifies the privacy
 * decision that was denied — the mutation's own operation for a
 * pre-write rejection, or [LOAD] when returned-entity disclosure was
 * denied after the mutation itself was allowed.
 */
enum class EntOperation {
    LOAD,
    QUERY,
    CREATE,
    UPDATE,
    DELETE,
    EDGE_MUTATION,
}
