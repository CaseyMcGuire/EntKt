package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

enum class FriendshipStatus { PENDING, ACCEPTED }

/**
 * A friendship request between two users. The junction holds state
 * (PENDING vs ACCEPTED) so it's a first-class entity, not a
 * transparent M2M join.
 */
class Friendship : EntSchema("friendships", clientName = "friendships") {
    override fun id() = EntId.int()
    val status by enum<FriendshipStatus>("status")

    val requester by belongsTo<User>("requester").inverse(User::sentRequests)
    val recipient by belongsTo<User>("recipient").inverse(User::receivedRequests)

    val idx = index("idx_friendships_requester_id_recipient_id_unique", requester.fk, recipient.fk).unique()
}
