package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

enum class TagCategory { TOPIC, LANGUAGE, AUDIENCE }

/**
 * A tag with an enum category. Tags are independent — no edges.
 */
class Tag : EntSchema("tags") {
    override fun id() = EntId.int()
    val name = string("name").unique()
    val category = enum<TagCategory>("category")
}
