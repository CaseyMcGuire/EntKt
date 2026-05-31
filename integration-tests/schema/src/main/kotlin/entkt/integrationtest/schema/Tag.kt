package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

class Tag : EntSchema("tags") {
    override fun id() = EntId.long()

    val name = string("name").unique()

    // RFC 10: the symmetric reverse of `Post.tags` — same `PostTag` junction,
    // FK pair swapped. Both orientations are writable by default, so this side
    // gets the full add/remove/set surface and idempotent junction inserts.
    val posts = manyToMany<Post>("posts")
        .throughLink<PostTag>(PostTag::tag, PostTag::post)
}
