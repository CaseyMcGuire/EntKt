package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete

/**
 * Junction table for the `Post.tags` link-table M2M edge. Satisfies
 * the RFC #3 helper-eligibility shape:
 *   1. id + the two FK columns (no payload)
 *   2. both junction belongsTo edges are non-null
 *   3. backing fields carry no write-time modifiers
 *   4. both FKs declare OnDelete.CASCADE explicitly
 *   5. id strategy is AUTO_LONG (not EXPLICIT)
 *   6. non-partial unique composite index on (post_id, tag_id) in
 *      source-first order
 */
class PostTag : EntSchema("post_tags") {
    override fun id() = EntId.long()

    val post = belongsTo<Post>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<Tag>("tag").onDelete(OnDelete.CASCADE)

    val pair = index("idx_post_tags_post_tag", post.fk, tag.fk).unique()
}
