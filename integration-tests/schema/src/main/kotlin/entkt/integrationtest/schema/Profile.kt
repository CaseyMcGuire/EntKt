package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * hasOne fixture: the only one-to-one relationship in the
 * integration schema. [User.profile] is the hasOne side; this
 * entity holds the unique FK, as every hasOne inverse must
 * (codegen rejects a non-`.unique()` inverse). Exercises the
 * eager executor's has-one path — grouped like hasMany, attached
 * as a single nullable value like belongsTo, and with the
 * window quirk that any positive offset steps past the at-most-one
 * candidate.
 */
class Profile : EntSchema("profiles", clientName = "profiles") {
    override fun id() = EntId.long()

    val owner by belongsTo<User>("owner").unique().inverse(User::profile)

    val bio by string("bio")
}
