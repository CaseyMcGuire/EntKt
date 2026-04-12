package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields

/**
 * A blog post that points back at its author. The `from("author", User).unique()`
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
        from("author", User).ref("posts").unique().required()
    }
}
