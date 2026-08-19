package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

class Tag : EntSchema("tags", clientName = "tags") {
    override fun id() = EntId.long()

    val name by string("name").unique()

    // symmetric link-table writes: the symmetric reverse of `Post.tags` — same `PostTag` junction,
    // FK pair swapped. Both orientations are writable by default, so this side
    // gets the full add/remove/set surface and idempotent junction inserts.
    val posts by manyToMany<Post>("posts")
        .throughLink<PostTag>(PostTag::tag, PostTag::post)
}
