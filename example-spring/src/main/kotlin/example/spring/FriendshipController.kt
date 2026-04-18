package example.spring

import example.ent.EntClient
import example.ent.Friendship
import example.schema.FriendshipStatus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class FriendRequestBody(val recipientId: UUID)

data class FriendshipResponse(
    val id: Int,
    val status: FriendshipStatus,
    val requesterId: UUID,
    val recipientId: UUID,
)

fun Friendship.toResponse() = FriendshipResponse(
    id = id,
    status = status,
    requesterId = requesterId,
    recipientId = recipientId,
)

@RestController
class FriendshipController(private val client: EntClient) {

    @PostMapping("/users/{id}/friends")
    fun sendRequest(@PathVariable id: UUID, @RequestBody req: FriendRequestBody): FriendshipResponse {
        val requester = client.users.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        val recipient = client.users.byId(req.recipientId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient not found")

        val friendship = client.friendships.create {
            this.requester = requester
            this.recipient = recipient
            status = FriendshipStatus.PENDING
        }.save()
        return friendship.toResponse()
    }

    @PostMapping("/friendships/{id}/accept")
    fun accept(@PathVariable id: Int): FriendshipResponse {
        val friendship = client.friendships.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val updated = client.friendships.update(friendship) {
            status = FriendshipStatus.ACCEPTED
        }.saveOrThrow()
        return updated.toResponse()
    }

    @GetMapping("/users/{id}/friends")
    fun listFriends(@PathVariable id: UUID): List<UserResponse> {
        client.users.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        val accepted = client.friendships.query {
            where(
                (Friendship.requesterId eq id) or (Friendship.recipientId eq id),
            )
            where(Friendship.status eq FriendshipStatus.ACCEPTED)
        }.all()

        val friendIds = accepted.map { f ->
            if (f.requesterId == id) f.recipientId else f.requesterId
        }

        return friendIds.mapNotNull { client.users.byId(it) }.map { it.toResponse() }
    }

    @GetMapping("/users/{id}/friend-requests")
    fun listPendingRequests(@PathVariable id: UUID): List<FriendshipResponse> {
        client.users.byId(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        return client.friendships.query {
            where(Friendship.recipientId eq id)
            where(Friendship.status eq FriendshipStatus.PENDING)
        }.all().map { it.toResponse() }
    }
}
