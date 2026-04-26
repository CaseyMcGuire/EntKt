package example.schema

import entkt.schema.EntId

/**
 * A user with a UUID primary key, a unique email, and timestamps.
 */
class User : TimestampedSchema("users") {
    override fun id() = EntId.uuid()

    val name = string("name").minLen(1).maxLen(64)
    val email = string("email").unique()
    val age = int("age").optional().min(0).max(150)
    val active = bool("active").default(true)

    val posts = hasMany<Post>("posts")
    val sentRequests = hasMany<Friendship>("sent_requests")
    val receivedRequests = hasMany<Friendship>("received_requests")
}
