package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

class User : EntSchema("users") {
    override fun id() = EntId.long()

    val name = string("name")
    val email = string("email").unique()

    val articles = hasMany<Article>("articles")
}
