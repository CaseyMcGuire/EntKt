package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Junction table for the Post ↔ Tag many-to-many relationship.
 */
class PostTag : EntSchema("post_tags") {
    override fun id() = EntId.int()

    val post = belongsTo<Post>("post").required()
    val tag = belongsTo<Tag>("tag").required()

    val idx = index("idx_post_tags_post_id_tag_id_unique", post.fk, tag.fk).unique()
}
