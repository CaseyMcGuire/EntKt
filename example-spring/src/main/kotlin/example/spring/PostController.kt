package example.spring

import example.ent.EntClient
import example.ent.Post
import example.ent.User
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
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

@RestController
@RequestMapping("/posts")
class PostController(private val client: EntClient) {

    @GetMapping
    fun list(@RequestParam published: Boolean?): List<PostResponse> {
        val posts = client.posts.query {
            if (published != null) where(Post.published eq published)
            orderBy(Post.createdAt.desc())
        }.all()
        return posts.map { it.toResponse() }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): PostResponse {
        val post = client.posts.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return post.toResponse()
    }

    @PostMapping
    fun create(@RequestBody req: CreatePostRequest): PostResponse {
        // Verify author exists
        val author = client.users.byId(req.authorId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Author not found")

        val post = client.posts.create {
            title = req.title
            body = req.body
            published = req.published
            this.author = author
        }.save()
        return post.toResponse()
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody req: UpdatePostRequest): PostResponse {
        val post = client.posts.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val updated = client.posts.update(post) {
            req.title?.let { title = it }
            req.body?.let { body = it }
            req.published?.let { published = it }
        }.saveOrThrow()
        return updated.toResponse()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long) {
        if (!client.posts.deleteById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }
}
