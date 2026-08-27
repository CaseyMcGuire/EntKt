package example.spring.users

import example.ent.EntClient
import example.ent.Post
import example.ent.User
import example.spring.auth.AuthContext
import example.spring.auth.viewerContext
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
class UserController(
    private val client: EntClient,
    private val auth: AuthContext,
) {

    @GetMapping
    fun list(
        @RequestParam active: Boolean?,
        @RequestParam(defaultValue = "false") includePosts: Boolean,
    ): List<UserResponse> {
        val viewerContext = auth.viewerContext()
        val users = client.users.query {
            if (active != null) where(User.active eq active)
            orderBy(User.name.asc())
            if (includePosts) {
                loadPosts { orderBy(Post.createdAt.desc()) }
            }
        }.all(viewerContext).getOrThrow()
        return users.map { it.toResponse(includePosts) }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): UserResponse {
        val viewerContext = auth.viewerContext()
        val user = client.users.findById(viewerContext, id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return user.toResponse()
    }

    @GetMapping("/{id}/posts")
    fun posts(@PathVariable id: UUID): List<PostResponse> {
        val viewerContext = auth.viewerContext()
        client.users.findById(viewerContext, id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val posts = client.posts.query {
            where(Post.authorId eq id)
            orderBy(Post.createdAt.desc())
        }.all(viewerContext).getOrThrow()
        return posts.map { it.toResponse() }
    }

    @PostMapping
    fun create(@RequestBody req: CreateUserRequest): UserResponse {
        val viewerContext = auth.viewerContext()
        val user = client.users.create {
            name = req.name
            email = req.email
            age = req.age
            active = req.active
        }.saveAndLoad(viewerContext).getOrThrow()
        return user.toResponse()
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody req: UpdateUserRequest): UserResponse {
        val viewerContext = auth.viewerContext()
        // No pre-load: `update(id)` does its own internal byId before
        // hooks/privacy/validation. A missing row surfaces as
        // Failed(EntTargetAbsentException), mapped to 404 by ErrorHandler.
        val updated = client.users.update(id) {
            req.name?.let { name = it }
            req.email?.let { email = it }
            req.age?.let { age = it }
            req.active?.let { active = it }
        }.saveAndLoad(viewerContext).getOrThrow()
        return updated.toResponse()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID) {
        val viewerContext = auth.viewerContext()
        if (!client.users.deleteById(viewerContext, id).getOrThrow()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }
}
