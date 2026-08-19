package example.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * A user with a UUID primary key, a unique email, and timestamps.
 */
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val timestamps = include(::Timestamps)

    val name by string("name").minLength(1).maxLength(64)
    val email by string("email").unique()
    val age by int("age").nullable().min(0).max(150)
    val active by bool("active").default(true)

    val posts by hasMany<Post>("posts")
    val sentRequests by hasMany<Friendship>("sent_requests")
    val receivedRequests by hasMany<Friendship>("received_requests")
}
