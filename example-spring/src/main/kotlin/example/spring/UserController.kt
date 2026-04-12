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

@RestController
@RequestMapping("/users")
class UserController(private val client: EntClient) {

    @GetMapping
    fun list(
        @RequestParam active: Boolean?,
        @RequestParam(defaultValue = "false") includePosts: Boolean,
    ): List<UserResponse> {
        val users = client.users.query {
            if (active != null) where(User.active eq active)
            orderBy(User.name.asc())
            if (includePosts) {
                withPosts { orderBy(Post.createdAt.desc()) }
            }
        }.all()
        return users.map { it.toResponse(includePosts) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): UserResponse {
        val user = client.users.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return user.toResponse()
    }

    @GetMapping("/{id}/posts")
    fun posts(@PathVariable id: UUID): List<PostResponse> {
        client.users.byId(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val posts = client.posts.query {
            where(Post.authorId eq id)
            orderBy(Post.createdAt.desc())
        }.all()
        return posts.map { it.toResponse() }
    }

    @PostMapping
    fun create(@RequestBody req: CreateUserRequest): UserResponse {
        val user = client.users.create {
            name = req.name
            email = req.email
            age = req.age
            active = req.active
        }.save()
        return user.toResponse()
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody req: UpdateUserRequest): UserResponse {
        val user = client.users.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val updated = client.users.update(user) {
            req.name?.let { name = it }
            req.email?.let { email = it }
            req.age?.let { age = it }
            req.active?.let { active = it }
        }.saveOrThrow()
        return updated.toResponse()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID) {
        if (!client.users.deleteById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }
}
