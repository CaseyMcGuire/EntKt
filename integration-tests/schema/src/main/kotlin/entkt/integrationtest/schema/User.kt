package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

class User : EntSchema("users") {
    override fun id() = EntId.long()

    val name = string("name")
    val email = string("email").unique()

    val articles = hasMany<Article>("articles")

    /**
     * Inverse side of [Group.users]. Pair-swapped junction edge
     * refs: this side passes `Membership::user` first (the
     * source-side junction edge), `Membership::group` second
     * (the target-side junction edge). The nullable M2M traversal acceptance
     * criteria require null-skip semantics to hold in both
     * directions; this edge is what exercises the inverse half.
     */
    val groups = manyToMany<Group>("groups")
        .throughEntity<Membership>(Membership::user, Membership::group)
}
