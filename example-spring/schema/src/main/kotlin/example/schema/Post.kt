package example.schema

import entkt.schema.EntId

/**
 * A blog post that points back at its author. The `belongsTo<User>("author")`
 * edge synthesizes an `authorId: UUID?` FK on Post.
 */
class Post : TimestampedSchema("posts") {
    override fun id() = EntId.long()

    val title = string("title").minLen(1).maxLen(200)
    val body = text("body")
    val published = bool("published").default(false)

    val author = belongsTo<User>("author").inverse(User::posts).required()
}
