package entkt.integrationtest.required.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

class User : EntSchema("required_has_one_users", clientName = "users") {
    override fun id() = EntId.long()

    val name by string("name")
    val profile by hasOne<Profile>().required()
}
