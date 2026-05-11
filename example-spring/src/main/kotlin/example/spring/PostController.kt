package example.spring

import example.ent.EntClient
import example.ent.Post
import example.ent.PostTag
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
        val updated = client.posts.update(post.id) {
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

    /** List tags on a post via the M2M junction. */
    @GetMapping("/{id}/tags")
    fun tags(@PathVariable id: Long): List<TagResponse> {
        client.posts.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val postTags = client.postTags.query {
            where(PostTag.postId eq id)
            withTag()
        }.all()
        return postTags.mapNotNull { it.edges.tag?.toResponse() }
    }

    /** Add a tag to a post. Idempotent — re-tagging is a no-op. */
    @PostMapping("/{id}/tags")
    fun addTag(@PathVariable id: Long, @RequestBody req: AddTagRequest): TagResponse {
        val post = client.posts.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val tag = client.tags.byId(req.tagId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Tag not found")
        val alreadyLinked = client.postTags.query {
            where((PostTag.postId eq id) and (PostTag.tagId eq req.tagId))
        }.exists()
        if (!alreadyLinked) {
            client.postTags.create {
                this.post = post
                this.tag = tag
            }.save()
        }
        return tag.toResponse()
    }

    /** Remove a tag from a post. */
    @DeleteMapping("/{postId}/tags/{tagId}")
    fun removeTag(@PathVariable postId: Long, @PathVariable tagId: Int) {
        client.posts.byId(postId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val deleted = client.postTags.deleteMany(
            (PostTag.postId eq postId) and (PostTag.tagId eq tagId),
        )
        if (deleted == 0) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }
}