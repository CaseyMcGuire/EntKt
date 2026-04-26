package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

enum class FriendshipStatus { PENDING, ACCEPTED }

/**
 * A friendship request between two users. The junction holds state
 * (PENDING vs ACCEPTED) so it's a first-class entity, not a
 * transparent M2M join.
 */
class Friendship : EntSchema("friendships") {
    override fun id() = EntId.int()
    val status = enum<FriendshipStatus>("status")

    val requester = belongsTo<User>("requester").inverse(User::sentRequests).required()
    val recipient = belongsTo<User>("recipient").inverse(User::receivedRequests).required()

    val idx = index("idx_requester_recipient", requester.fk, recipient.fk).unique()
}
