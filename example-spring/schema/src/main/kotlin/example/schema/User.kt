package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * A user with a UUID primary key, a unique email, and timestamps.
 */
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val timestamps = include(::Timestamps)

    val name by string("name")
    val email by string("email").unique()
    val age by int("age").nullable()
    val active by bool("active").default(true)

    val posts by hasMany<Post>()
    val sentRequests by hasMany<Friendship>()
    val receivedRequests by hasMany<Friendship>()
}
