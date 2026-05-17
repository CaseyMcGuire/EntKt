package example.spring.friendships

import example.ent.EntClient
import example.ent.Friendship
import example.schema.FriendshipStatus
import example.spring.users.UserResponse
import example.spring.users.toResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
class FriendshipController(private val client: EntClient) {

    @PostMapping("/users/{id}/friends")
    fun sendRequest(@PathVariable id: UUID, @RequestBody req: FriendRequestBody): FriendshipResponse {
        val requester = client.users.byIdOrNull(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        val recipient = client.users.byIdOrNull(req.recipientId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient not found")

        val friendship = client.friendships.create {
            this.requesterId = requester.id
            this.recipientId = recipient.id
            status = FriendshipStatus.PENDING
        }.save()
        return friendship.toResponse()
    }

    @PostMapping("/friendships/{id}/accept")
    fun accept(@PathVariable id: Int): FriendshipResponse {
        // No pre-load: `update(id)` does its own internal byId.
        // Missing-row → save() returns null → 404.
        val updated = client.friendships.update(id) {
            status = FriendshipStatus.ACCEPTED
        }.save() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return updated.toResponse()
    }

    @GetMapping("/users/{id}/friends")
    fun listFriends(@PathVariable id: UUID): List<UserResponse> {
        client.users.byIdOrNull(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        val accepted = client.friendships.query {
            where(
                (Friendship.requesterId eq id) or (Friendship.recipientId eq id),
            )
            where(Friendship.status eq FriendshipStatus.ACCEPTED)
        }.allOrThrow()

        val friendIds = accepted.map { f ->
            if (f.requesterId == id) f.recipientId else f.requesterId
        }

        return friendIds.mapNotNull { client.users.byIdOrNull(it) }.map { it.toResponse() }
    }

    @GetMapping("/users/{id}/friend-requests")
    fun listPendingRequests(@PathVariable id: UUID): List<FriendshipResponse> {
        client.users.byIdOrNull(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        return client.friendships.query {
            where(Friendship.recipientId eq id)
            where(Friendship.status eq FriendshipStatus.PENDING)
        }.allOrThrow().map { it.toResponse() }
    }
}