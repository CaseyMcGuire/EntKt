package example.spring

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
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers
class FriendshipIntegrationTest {

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

    private lateinit var aliceId: UUID
    private lateinit var bobId: UUID

    @BeforeEach
    fun setUp() {
        aliceId = createUser("Alice", "alice-${UUID.randomUUID()}@example.com")
        bobId = createUser("Bob", "bob-${UUID.randomUUID()}@example.com")
    }

    @Test
    fun `send friend request creates a pending friendship`() {
        mockMvc.post("/users/$aliceId/friends") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"recipientId": "$bobId"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.requesterId") { value(aliceId.toString()) }
            jsonPath("$.recipientId") { value(bobId.toString()) }
        }
    }

    @Test
    fun `accept friend request transitions to accepted`() {
        val friendshipId = sendFriendRequest(aliceId, bobId)

        mockMvc.post("/friendships/$friendshipId/accept")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("ACCEPTED") }
            }
    }

    @Test
    fun `list friends returns accepted friends only`() {
        val friendshipId = sendFriendRequest(aliceId, bobId)
        mockMvc.post("/friendships/$friendshipId/accept")

        mockMvc.get("/users/$aliceId/friends")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].id") { value(bobId.toString()) }
            }

        // Bob should also see Alice as a friend
        mockMvc.get("/users/$bobId/friends")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].id") { value(aliceId.toString()) }
            }
    }

    @Test
    fun `list friends excludes pending requests`() {
        sendFriendRequest(aliceId, bobId)

        mockMvc.get("/users/$aliceId/friends")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }

    @Test
    fun `list pending requests shows incoming requests`() {
        sendFriendRequest(aliceId, bobId)

        // Bob should see the pending request
        mockMvc.get("/users/$bobId/friend-requests")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].status") { value("PENDING") }
                jsonPath("$[0].requesterId") { value(aliceId.toString()) }
            }

        // Alice should not see it as a pending incoming request
        mockMvc.get("/users/$aliceId/friend-requests")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }

    @Test
    fun `cannot friend yourself`() {
        mockMvc.post("/users/$aliceId/friends") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"recipientId": "$aliceId"}"""
        }.andExpect {
            status { is4xxClientError() }
        }
    }

    @Test
    fun `cannot send duplicate friend request`() {
        sendFriendRequest(aliceId, bobId)

        mockMvc.post("/users/$aliceId/friends") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"recipientId": "$bobId"}"""
        }.andExpect {
            status { is4xxClientError() }
        }
    }

    @Test
    fun `cannot send reverse duplicate friend request`() {
        sendFriendRequest(aliceId, bobId)

        // Bob tries to send a request back to Alice — should be rejected
        mockMvc.post("/users/$bobId/friends") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"recipientId": "$aliceId"}"""
        }.andExpect {
            status { is4xxClientError() }
        }
    }

    private fun createUser(name: String, email: String): UUID {
        val result = mockMvc.post("/users") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name": "$name", "email": "$email"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn()

        val body = objectMapper.readTree(result.response.contentAsString)
        return UUID.fromString(body["id"].asText())
    }

    private fun sendFriendRequest(requesterId: UUID, recipientId: UUID): Int {
        val result = mockMvc.post("/users/$requesterId/friends") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"recipientId": "$recipientId"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn()

        val body = objectMapper.readTree(result.response.contentAsString)
        return body["id"].asInt()
    }
}
