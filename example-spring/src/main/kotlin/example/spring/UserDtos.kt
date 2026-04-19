package example.spring

import example.ent.User
import java.util.UUID

data class CreateUserRequest(
    val name: String,
    val email: String,
    val age: Int? = null,
    val active: Boolean = true,
)

data class UpdateUserRequest(
    val name: String? = null,
    val email: String? = null,
    val age: Int? = null,
    val active: Boolean? = null,
)

data class UserResponse(
    val id: UUID,
    val name: String,
    val email: String,
    val age: Int?,
    val active: Boolean,
    val posts: List<PostResponse>? = null,
)

fun User.toResponse(includePosts: Boolean = false) = UserResponse(
    id = id,
    name = name,
    email = email,
    age = age,
    active = active,
    posts = if (includePosts) edges.posts?.map { it.toResponse() } else null,
)