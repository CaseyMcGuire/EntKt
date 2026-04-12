package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields

/**
 * A user with a UUID primary key, a unique email, and timestamps.
 */
object User : EntSchema() {
    override fun id() = EntId.uuid()

    override fun mixins() = listOf(TimestampMixin)

    override fun fields() = fields {
        string("name").minLen(1).maxLen(64)
        string("email").unique()
        int("age").optional().min(0).max(150)
        bool("active").default(true)
    }

    override fun edges() = edges {
        to("posts", Post)
        to("sent_requests", Friendship)
        to("received_requests", Friendship)
    }
}
