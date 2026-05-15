package example.spring.tags

import example.ent.Tag
import example.schema.TagCategory

data class CreateTagRequest(
    val name: String,
    val category: TagCategory,
)

data class TagResponse(
    val id: Int,
    val name: String,
    val category: TagCategory,
)

fun Tag.toResponse() = TagResponse(
    id = id,
    name = name,
    category = category,
)
