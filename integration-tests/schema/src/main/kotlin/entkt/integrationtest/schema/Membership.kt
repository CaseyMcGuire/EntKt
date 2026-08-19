package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Domain-entity junction for the [Group] ↔ [User] many-to-many
 * relationship. Junction-shape rules doesn't apply here — this
 * is a `throughEntity` junction (a real domain entity callers
 * can `client.memberships.create { ... }.save()` directly), so
 * its FK columns may be nullable and it carries a payload.
 *
 * The nullable FKs are intentional and the subject of
 * `docs/implemented-features/edge-mutation/09-through-entity-nullable-m2m-traversal.md`:
 * rows with `group_id` or `user_id` set to NULL must be skipped
 * by every form of `Group.users` / `User.groups` M2M traversal
 * (query-chain, predicate, eager) while remaining directly
 * queryable through `client.memberships`.
 */
class Membership : EntSchema("memberships", clientName = "memberships") {
    override fun id() = EntId.long()

    val group by belongsTo<Group>("group").nullable()
    val user by belongsTo<User>("user").nullable()

    /** Payload — proves the junction is a domain entity, not just a join row. */
    val role by string("role")
}
