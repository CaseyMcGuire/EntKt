package example.schema

import entkt.schema.EntSchema
import entkt.schema.fields

/**
 * A tag with an enum category. Tags are independent — no edges.
 */
object Tag : EntSchema() {
    override fun fields() = fields {
        string("name").unique()
        enum("category").values("TOPIC", "LANGUAGE", "AUDIENCE")
    }
}
