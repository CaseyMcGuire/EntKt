package example.spring

import example.ent.Post
import java.util.UUID

data class CreatePostRequest(
    val title: String,
    val body: String,
    val authorId: UUID,
    val published: Boolean = false,
)

data class UpdatePostRequest(
    val title: String? = null,
    val body: String? = null,
    val published: Boolean? = null,
)

data class PostResponse(
    val id: Long,
    val title: String,
    val body: String,
    val published: Boolean,
    val authorId: UUID,
)

fun Post.toResponse() = PostResponse(
    id = id,
    title = title,
    body = body,
    published = published,
    authorId = authorId,
)