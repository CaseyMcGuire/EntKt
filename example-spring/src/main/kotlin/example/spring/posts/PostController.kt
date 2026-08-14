package example.spring.posts

import entkt.runtime.query.requireLoaded
import example.ent.EntClient
import example.ent.Post
import example.ent.PostTag
import example.spring.tags.TagResponse
import example.spring.tags.toResponse
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

@RestController
@RequestMapping("/posts")
class PostController(private val client: EntClient) {

    @GetMapping
    fun list(@RequestParam published: Boolean?): List<PostResponse> {
        val posts = client.posts.query {
            if (published != null) where(Post.published eq published)
            orderBy(Post.createdAt.desc())
        }.all().getOrThrow()
        return posts.map { it.toResponse() }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): PostResponse {
        val post = client.posts.findById(id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return post.toResponse()
    }

    @PostMapping
    fun create(@RequestBody req: CreatePostRequest): PostResponse {
        // Verify author exists
        val author = client.users.findById(req.authorId).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Author not found")

        val post = client.posts.create {
            title = req.title
            body = req.body
            published = req.published
            this.authorId = author.id
        }.saveAndLoad().getOrThrow()
        return post.toResponse()
    }

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody req: UpdatePostRequest): PostResponse {
        // No pre-load: `update(id)` does its own internal byId.
        // Missing-row → Failed(EntTargetAbsentException) → 404 via ErrorHandler.
        val updated = client.posts.update(id) {
            req.title?.let { title = it }
            req.body?.let { body = it }
            req.published?.let { published = it }
        }.saveAndLoad().getOrThrow()
        return updated.toResponse()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long) {
        if (!client.posts.deleteById(id).getOrThrow()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    /**
     * List tags on a post.
     *
     * Demonstrates the generated forward M2M *eager-load* surface
     * (`PostQuery.withTags { }`) declared by `Post.tags =
     * manyToMany<Tag>(...).throughEntity<PostTag>(...)`. The query
     * fetches the matching `Post` row plus all related `Tag` rows in
     * the runtime's M2M-eager-load helper (junction-IN → target-IN);
     * the loaded list lands on the entity's generated `Edges.tags`
     * field as `EdgeState.Loaded(tags)`. Compare to the shaped traversal in
     * [TagController.posts]: this path returns the tags on a single
     * known post via eager loading; that path returns the posts that
     * carry a single known tag via `queryPosts()` lowering to
     * `Predicate.HasM2MEdgeFromShape`.
     */
    @GetMapping("/{id}/tags")
    fun tags(@PathVariable id: Long): List<TagResponse> {
        val post = client.posts.query {
            where(Post.id eq id)
            withTags()
        }.firstOrNull().getOrThrow() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return post.edges.tags.requireLoaded().map { it.toResponse() }
    }

    /** Add a tag to a post. Idempotent — re-tagging is a no-op. */
    @PostMapping("/{id}/tags")
    fun addTag(@PathVariable id: Long, @RequestBody req: AddTagRequest): TagResponse {
        val post = client.posts.findById(id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val tag = client.tags.findById(req.tagId).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag not found")
        val alreadyLinked = client.postTags.query {
            where((PostTag.postId eq id) and (PostTag.tagId eq req.tagId))
        }.rawExists().getOrThrow()
        if (!alreadyLinked) {
            client.postTags.create {
                this.postId = post.id
                this.tagId = tag.id
            }.save().getOrThrow()
        }
        return tag.toResponse()
    }

    /** Remove a tag from a post. */
    @DeleteMapping("/{postId}/tags/{tagId}")
    fun removeTag(@PathVariable postId: Long, @PathVariable tagId: Int) {
        client.posts.findById(postId).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val deleted = client.postTags.deleteMany(
            (PostTag.postId eq postId) and (PostTag.tagId eq tagId),
        ).getOrThrow()
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }
}