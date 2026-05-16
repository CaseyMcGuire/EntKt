package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

class Tag : EntSchema("tags") {
    override fun id() = EntId.long()

    val name = string("name").unique()
}
