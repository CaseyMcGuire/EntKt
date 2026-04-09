package example

import entkt.schema.EntId
import entkt.schema.EntMixin
import entkt.schema.EntSchema
import entkt.schema.edges
import entkt.schema.fields

/**
 * A reusable mixin that stamps `created_at` and `updated_at` on every
 * schema that includes it. `created_at` is immutable — the update
 * builder won't get a setter for it.
 */
object TimestampMixin : EntMixin {
    override fun fields() = fields {
        time("created_at").immutable()
        time("updated_at")
    }
}

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
        // A user has many posts. No FK on User — Post stores it.
        to("posts", Post)
    }
}

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

/**
 * A tag with an enum category. Tags are independent — no edges.
 */
object Tag : EntSchema() {
    override fun fields() = fields {
        string("name").unique()
        enum("category").values("TOPIC", "LANGUAGE", "AUDIENCE")
    }
}