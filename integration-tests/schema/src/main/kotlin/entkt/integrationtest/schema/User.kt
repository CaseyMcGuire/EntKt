package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields

object User : EntSchema() {
    override fun id() = EntId.long()

    override fun fields() = fields {
        string("name")
        string("email").unique()
    }

    override fun edges() = edges {
        hasMany("articles", Article)
    }
}