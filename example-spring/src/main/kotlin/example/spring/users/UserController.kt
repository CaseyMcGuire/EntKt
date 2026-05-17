package example.spring.users

import entkt.runtime.getOrThrow
import example.ent.EntClient
import example.ent.Post
import example.ent.User
import example.spring.posts.PostResponse
import example.spring.posts.toResponse
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
        }.allOrThrow()
        return users.map { it.toResponse(includePosts) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): UserResponse {
        val user = client.users.byIdOrNull(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return user.toResponse()
    }

    @GetMapping("/{id}/posts")
    fun posts(@PathVariable id: UUID): List<PostResponse> {
        client.users.byIdOrNull(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val posts = client.posts.query {
            where(Post.authorId eq id)
            orderBy(Post.createdAt.desc())
        }.allOrThrow()
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
        // No pre-load: `update(id)` does its own internal byId before
        // hooks/privacy/validation. A missing row makes save() return
        // null, which we map to 404 here.
        val updated = client.users.update(id) {
            req.name?.let { name = it }
            req.email?.let { email = it }
            req.age?.let { age = it }
            req.active?.let { active = it }
        }.save() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return updated.toResponse()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID) {
        if (!client.users.deleteByIdOrError(id).getOrThrow()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }
}