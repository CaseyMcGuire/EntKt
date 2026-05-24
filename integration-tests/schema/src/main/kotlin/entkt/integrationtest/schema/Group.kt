package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Source side of the RFC 09 fixture. The `users` edge is a
 * `throughEntity` M2M whose junction ([Membership]) carries
 * nullable source / target FKs. Traversal must skip junction
 * rows whose `group_id` or `user_id` is NULL.
 *
 * Paired with `User.groups` on the existing [User] schema for
 * inverse-direction coverage.
 */
class Group : EntSchema("groups") {
    override fun id() = EntId.long()

    val name = string("name")

    val users = manyToMany<User>("users")
        .throughEntity<Membership>(Membership::group, Membership::user)
}
