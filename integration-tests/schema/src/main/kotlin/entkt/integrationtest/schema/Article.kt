package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields

object Article : EntSchema() {
    override fun id() = EntId.long()

    override fun fields() = fields {
        string("title")
        bool("published").default(false)
    }

    override fun edges() = edges {
        belongsTo("author", User).ref("articles").required()
    }
}