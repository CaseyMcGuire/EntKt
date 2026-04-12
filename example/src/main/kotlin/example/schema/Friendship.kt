package example.schema

import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields
import entkt.schema.indexes

/**
 * A friendship request between two users. The junction holds state
 * (PENDING vs ACCEPTED) so it's a first-class entity, not a
 * transparent M2M join.
 */
object Friendship : EntSchema() {
    override fun fields(): List<entkt.schema.Field> = fields {
        enum("status").values("PENDING", "ACCEPTED")
    }

    override fun edges(): List<entkt.schema.Edge> = edges {
        from("requester", User).ref("sent_requests").unique().required()
        from("recipient", User).ref("received_requests").unique().required()
    }

    override fun indexes(): List<entkt.schema.Index> = indexes {
        index("requester_id", "recipient_id").unique()
    }
}
