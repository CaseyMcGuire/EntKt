package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete

/**
 * Junction table for the Post ↔ Tag many-to-many relationship.
 */
class PostTag : EntSchema("post_tags", clientName = "postTags") {
    override fun id() = EntId.int()

    val post by belongsTo<Post>("post").onDelete(OnDelete.CASCADE)
    val tag by belongsTo<Tag>("tag").onDelete(OnDelete.CASCADE)

    val idx = index("idx_post_tags_post_id_tag_id_unique", post.fk, tag.fk).unique()
    val idxTagId = index("idx_post_tags_tag_id", tag.fk)
}
