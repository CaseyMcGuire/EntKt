package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.long()

    val name by string("name")
    val email by string("email").unique()
    // Exercises the framework-wide .sensitive() display contract end to end
    // (generated toString redaction + the ent viewer's redacted cells).
    val apiToken by string("api_token").nullable().sensitive()

    val articles by hasMany<Article>("articles")

    /**
     * Inverse of [Directory.curator]. The declaration is `directories`
     * while the storage edge name is `legacy_owner`, so the generated
     * traversal is `queryDirectories()` — nothing derived from storage
     * or from the `Directory` type.
     */
    val directories by hasMany<Directory>("legacy_owner")

    /**
     * Inverse side of [Group.users]. Pair-swapped junction edge
     * refs: this side passes `Membership::user` first (the
     * source-side junction edge), `Membership::group` second
     * (the target-side junction edge). The nullable M2M traversal acceptance
     * criteria require null-skip semantics to hold in both
     * directions; this edge is what exercises the inverse half.
     */
    val groups by manyToMany<Group>("groups")
        .throughEntity<Membership>(Membership::user, Membership::group)
}
