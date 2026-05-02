package example.spring

import example.ent.EntClient
import example.ent.PostTag
import example.ent.Tag
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
class TagController(private val client: EntClient) {

    @GetMapping
    fun list(): List<TagResponse> {
        val tags = client.tags.query {
            orderBy(Tag.name.asc())
        }.all()
        return tags.map { it.toResponse() }
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Int): TagResponse {
        val tag = client.tags.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return tag.toResponse()
    }

    @PostMapping
    fun create(@RequestBody req: CreateTagRequest): TagResponse {
        val tag = client.tags.create {
            name = req.name
            category = req.category
        }.save()
        return tag.toResponse()
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Int) {
        if (!client.tags.deleteById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    /** List posts that have this tag, via the M2M junction. */
    @GetMapping("/{id}/posts")
    fun posts(@PathVariable id: Int): List<PostResponse> {
        client.tags.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val postTags = client.postTags.query {
            where(PostTag.tagId eq id)
            withPost()
        }.all()
        return postTags.mapNotNull { it.edges.post?.toResponse() }
    }
}
