package example.spring.viewer

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The RFC's stated purpose for example-spring: validate that a thin Spring
 * wrapper over the framework-neutral core works — servlet request in,
 * viewer response out, with the app's real auth filter and privacy
 * policies in the loop.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class EntViewerEndpointIntegrationTest {

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

    private fun createUser(name: String): UUID {
        val result = mockMvc.post("/users") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "$name", "email": "$name-${UUID.randomUUID()}@example.com"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return UUID.fromString(objectMapper.readTree(result.response.contentAsString)["id"].asText())
    }

    @Test
    fun `unauthenticated requests are rejected by the viewer's authorize callback`() {
        mockMvc.get("/_ent").andExpect { status { isForbidden() } }
    }

    @Test
    fun `authenticated users get the viewer under their own privacy context`() {
        val userId = createUser("viewer-admin")
        val home = mockMvc.get("/_ent") { header("X-User-Id", userId.toString()) }
            .andExpect { status { isOk() } }
            .andReturn().response.contentAsString
        assertTrue("EntKt Viewer" in home)
        assertTrue("Post" in home)

        mockMvc.get("/_ent/entities/user") { header("X-User-Id", userId.toString()) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `writes and unknown entities are refused`() {
        val userId = createUser("viewer-admin2")
        mockMvc.post("/_ent/entities/user") { header("X-User-Id", userId.toString()) }
            .andExpect { status { isMethodNotAllowed() } }
        mockMvc.get("/_ent/entities/ghost") { header("X-User-Id", userId.toString()) }
            .andExpect { status { isNotFound() } }
    }
}
