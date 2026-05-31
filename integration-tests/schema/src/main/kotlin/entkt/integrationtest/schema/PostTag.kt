package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete

/**
 * Junction table for the symmetric `Post.tags` / `Tag.posts` link-table
 * M2M edge (symmetric link-table writes). Satisfies the M2M schema modeling helper-eligibility shape:
 *   1. id + the two FK columns (no payload)
 *   2. both junction belongsTo edges are non-null
 *   3. backing fields carry no write-time modifiers
 *   4. both FKs declare OnDelete.CASCADE explicitly
 *   5. id strategy is AUTO_LONG (not EXPLICIT)
 *   6. a non-partial unique index over the (unordered) FK pair, plus —
 *      for each declared orientation — a non-partial index leading with
 *      that side's source FK (post_id for `Post.tags`, tag_id for
 *      `Tag.posts`).
 */
class PostTag : EntSchema("post_tags") {
    override fun id() = EntId.long()

    val post = belongsTo<Post>("post").onDelete(OnDelete.CASCADE)
    val tag = belongsTo<Tag>("tag").onDelete(OnDelete.CASCADE)

    // Unique pair index — the conflict target for idempotent insertIgnore and
    // the leading-column index for the `Post.tags` orientation (leads post_id).
    val pair = index("idx_post_tags_post_tag", post.fk, tag.fk).unique()

    // symmetric link-table writes: the `Tag.posts` orientation needs its own non-partial index
    // leading with its source FK (tag_id) to validate.
    val byTag = index("idx_post_tags_tag_post", tag.fk, post.fk)
}
