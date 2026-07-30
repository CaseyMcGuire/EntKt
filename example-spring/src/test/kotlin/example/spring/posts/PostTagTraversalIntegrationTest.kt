package example.spring.posts

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * Exercises the bidirectional `Post.tags` / `Tag.posts` M2M traversal
 * through generated forward query traversal — the runtime path
 * introduced when forward-edge M2M lowering replaced synthesized
 * reverse-edge metadata.
 *
 * `PostController.tags(id)` lowers to `PostQuery.withTags { }` (M2M
 * eager-load helper); `TagController.posts(id)` lowers to
 * `TagQuery.queryPosts()` → `Predicate.HasM2MEdgeFromShape("posts",
 * <shaped tag source>)` → a junction walk fed by a shaped source
 * subquery. Both declarations share canonical identity
 * `{PostTag, post, tag}`; nothing is synthesized on the
 * opposite-side schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class PostTagTraversalIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
        }
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper

    private lateinit var authorId: UUID

    @BeforeEach
    fun setUp() {
        authorId = createUser("Author", "author-${UUID.randomUUID()}@example.com")
    }

    @Test
    fun `posts on a tag returns each linked post exactly once via queryPosts traversal`() {
        // Three posts; tag two of them with "kotlin", one with "java".
        val kotlinTagId = createTag("kotlin", "TOPIC")
        val javaTagId = createTag("java", "TOPIC")

        val postA = createPost("Post A")
        val postB = createPost("Post B")
        val postC = createPost("Post C")

        addTag(postA, kotlinTagId)
        addTag(postB, kotlinTagId)
        addTag(postC, javaTagId)

        // GET /tags/{kotlinTagId}/posts → posts A and B (both carry the
        // kotlin tag), exactly once each, no java-tagged post.
        mockMvc.get("/tags/$kotlinTagId/posts")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
            }

        val kotlinPostsBody = mockMvc.get("/tags/$kotlinTagId/posts").andReturn()
            .response.contentAsString
        val kotlinPostIds = objectMapper.readTree(kotlinPostsBody)
            .map { it["id"].asLong() }
            .toSet()
        assert(kotlinPostIds == setOf(postA, postB)) {
            "Expected kotlin tag's posts to be {$postA, $postB}, got $kotlinPostIds"
        }

        // The java tag should produce post C only.
        mockMvc.get("/tags/$javaTagId/posts")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].id") { value(postC) }
            }
    }

    @Test
    fun `tags on a post returns each linked tag exactly once via withTags eager load`() {
        val kotlinTagId = createTag("kotlin", "TOPIC")
        val webTagId = createTag("web", "TOPIC")
        val unusedTagId = createTag("unused", "AUDIENCE")

        val postId = createPost("Two-tagged post")
        addTag(postId, kotlinTagId)
        addTag(postId, webTagId)

        val tagsBody = mockMvc.get("/posts/$postId/tags")
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        val tagIds = objectMapper.readTree(tagsBody)
            .map { it["id"].asInt() }
            .toSet()

        assert(tagIds == setOf(kotlinTagId, webTagId)) {
            "Expected post's tags to be {kotlin, web}, got $tagIds (unused tag id $unusedTagId should not appear)"
        }
    }

    @Test
    fun `posts on a missing tag returns 404`() {
        mockMvc.get("/tags/9999/posts")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `tags on a missing post returns 404`() {
        mockMvc.get("/posts/9999/tags")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `posts on a tag with no links returns an empty list, not 404`() {
        val emptyTagId = createTag("orphan", "TOPIC")
        mockMvc.get("/tags/$emptyTagId/posts")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }

    // ---------- helpers ----------

    private fun createUser(name: String, email: String): UUID {
        val result = mockMvc.post("/users") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "$name", "email": "$email"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return UUID.fromString(objectMapper.readTree(result.response.contentAsString)["id"].asText())
    }

    private fun createTag(name: String, category: String): Int {
        val result = mockMvc.post("/tags") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "$name-${UUID.randomUUID()}", "category": "$category"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asInt()
    }

    private fun createPost(title: String): Long {
        val result = mockMvc.post("/posts") {
            contentType = MediaType.APPLICATION_JSON
            // PostPolicy.RequireAuthToCreate rejects anonymous viewers,
            // so the AuthFilter needs an X-User-Id header to set the
            // viewer to a real user.
            header("X-User-Id", authorId.toString())
            content = """{"title": "$title", "body": "body of $title", "published": true, "authorId": "$authorId"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asLong()
    }

    private fun addTag(postId: Long, tagId: Int) {
        mockMvc.post("/posts/$postId/tags") {
            contentType = MediaType.APPLICATION_JSON
            header("X-User-Id", authorId.toString())
            content = """{"tagId": $tagId}"""
        }.andExpect { status { isOk() } }
    }
}
