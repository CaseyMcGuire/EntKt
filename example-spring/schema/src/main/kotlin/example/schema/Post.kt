package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields

/**
 * A blog post that points back at its author. The `belongsTo("author", User)`
 * edge synthesizes an `authorId: UUID?` FK on Post.
 */
object Post : EntSchema() {
    override fun id() = EntId.long()

    override fun mixins() = listOf(TimestampMixin)

    override fun fields() = fields {
        string("title").minLen(1).maxLen(200)
        text("body")
        bool("published").default(false)
    }

    override fun edges() = edges {
        belongsTo("author", User).ref("posts").required()
    }
}
