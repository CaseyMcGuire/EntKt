package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

enum class TagCategory { TOPIC, LANGUAGE, AUDIENCE }

/**
 * A tag with an enum category. Tags are independent — no edges.
 */
class Tag : EntSchema("tags", clientName = "tags") {
    override fun id() = EntId.int()
    val name by string("name").unique()
    val category by enum<TagCategory>("category")

    val posts by manyToMany<Post>("posts")
        .throughEntity<PostTag>(PostTag::tag, PostTag::post)
}
