package example.schema

import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields
import entkt.schema.indexes

enum class FriendshipStatus { PENDING, ACCEPTED }

/**
 * A friendship request between two users. The junction holds state
 * (PENDING vs ACCEPTED) so it's a first-class entity, not a
 * transparent M2M join.
 */
object Friendship : EntSchema() {
    override fun fields(): List<entkt.schema.Field> = fields {
        enum<FriendshipStatus>("status")
    }

    override fun edges(): List<entkt.schema.Edge> = edges {
        belongsTo("requester", User).ref("sent_requests").required()
        belongsTo("recipient", User).ref("received_requests").required()
    }

    override fun indexes(): List<entkt.schema.Index> = indexes {
        index("requester_id", "recipient_id").unique()
    }
}
