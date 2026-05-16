package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Test schema for RFC #5 link-table M2M coverage. `tags` is a
 * helper-eligible `throughLink` edge whose junction satisfies every
 * shape constraint from RFC #3 (id + two non-null CASCADE FKs only,
 * source-first non-partial unique composite index, no payload, AUTO_LONG
 * junction id).
 */
class Post : EntSchema("posts") {
    override fun id() = EntId.long()

    val title = string("title")

    val tags = manyToMany<Tag>("tags")
        .throughLink<PostTag>(PostTag::post, PostTag::tag)
}
