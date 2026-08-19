package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * A blog post that points back at its author. The `belongsTo<User>("author")`
 * edge synthesizes an `authorId: UUID` FK on Post.
 */
class Post : EntSchema("posts", clientName = "posts") {
    override fun id() = EntId.long()

    val timestamps = include(::Timestamps)

    val title by string("title").minLength(1).maxLength(200)
    val body by text("body")
    val published by bool("published").default(false)

    val author by belongsTo<User>("author").inverse(User::posts)
    val tags by manyToMany<Tag>("tags").throughEntity<PostTag>(PostTag::post, PostTag::tag)
}
