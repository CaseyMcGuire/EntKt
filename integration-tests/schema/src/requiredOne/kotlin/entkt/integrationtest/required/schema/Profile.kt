package entkt.integrationtest.required.schema

import entkt.schema.EntId
import entkt.schema.EntSchema
import entkt.schema.OnDelete

class Profile : EntSchema("required_has_one_profiles", clientName = "profiles") {
    override fun id() = EntId.long()

    val owner by belongsTo<User>("owner_id").unique().inverse(User::profile).onDelete(OnDelete.RESTRICT)
    val bio by string("bio")
}
