package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

class Article : EntSchema("articles") {
    override fun id() = EntId.long()

    val title = string("title")
    val published = bool("published").default(false)

    val author = belongsTo<User>("author").inverse(User::articles).required()
}
