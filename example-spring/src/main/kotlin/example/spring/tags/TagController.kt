package example.spring.tags

import example.ent.EntClient
import example.ent.Tag
import example.spring.auth.AuthContext
import example.spring.auth.viewerContext
import example.spring.posts.PostResponse
import example.spring.posts.toResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/tags")
class TagController(
    private val client: EntClient,
    private val auth: AuthContext,
) {

    @GetMapping
    fun list(): List<TagResponse> {
        val viewerContext = auth.viewerContext()
        val tags = client.tags.query {
            orderBy(Tag.name.asc())
        }.all(viewerContext).getOrThrow()
        return tags.map { it.toResponse() }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Int): TagResponse {
        val viewerContext = auth.viewerContext()
        val tag = client.tags.findById(viewerContext, id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return tag.toResponse()
    }

    @PostMapping
    fun create(@RequestBody req: CreateTagRequest): TagResponse {
        val viewerContext = auth.viewerContext()
        val tag = client.tags.create {
            name = req.name
            category = req.category
        }.saveAndLoad(viewerContext).getOrThrow()
        return tag.toResponse()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Int) {
        val viewerContext = auth.viewerContext()
        if (!client.tags.deleteById(viewerContext, id).getOrThrow()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    /**
     * List posts that have this tag.
     *
     * Uses the generated forward M2M traversal `TagQuery.queryPosts()`,
     * declared by `Tag.posts = manyToMany<Post>(...).throughEntity<PostTag>(...)`
     * in [example.schema.Tag]. The traversal lowers to a
     * `Predicate.HasM2MEdgeFromShape("posts", <shaped tag source>)`
     * evaluated against each candidate `Post` row — the shape carries
     * the tag query as written (predicates, order, limit, offset) —
     * and the runtime walks the junction backwards using `Tag`'s own
     * forward-edge metadata. No reverse-edge entry is synthesized on
     * `Post`'s schema — this is the explicit-API contract from M2M
     * schema modeling.
     */
    @GetMapping("/{id}/posts")
    fun posts(@PathVariable id: Int): List<PostResponse> {
        val viewerContext = auth.viewerContext()
        client.tags.findById(viewerContext, id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val posts = client.tags.query { where(Tag.id eq id) }
            .queryPosts()
            .all(viewerContext).getOrThrow()
        return posts.map { it.toResponse() }
    }
}
