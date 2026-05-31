package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Test schema for link-table M2M coverage. `tags` is a helper-eligible
 * `throughLink` edge whose junction satisfies every shape constraint from
 * RFC #3 (id + two non-null CASCADE FKs only, an unordered unique pair
 * index plus a per-side leading-column index, no payload, AUTO_LONG
 * junction id). Its symmetric reverse `Tag.posts` is declared on [Tag]
 * (RFC 10) — both orientations are writable.
 */
class Post : EntSchema("posts") {
    override fun id() = EntId.long()

    val title = string("title")

    val tags = manyToMany<Tag>("tags")
        .throughLink<PostTag>(PostTag::post, PostTag::tag)
}
