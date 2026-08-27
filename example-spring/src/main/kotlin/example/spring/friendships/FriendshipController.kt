package example.spring.friendships

import example.ent.EntClient
import example.ent.Friendship
import example.schema.FriendshipStatus
import example.spring.auth.AuthContext
import example.spring.auth.viewerContext
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
class FriendshipController(
    private val client: EntClient,
    private val auth: AuthContext,
) {

    @PostMapping("/users/{id}/friends")
    fun sendRequest(@PathVariable id: UUID, @RequestBody req: FriendRequestBody): FriendshipResponse {
        val viewerContext = auth.viewerContext()
        val requester = client.users.findById(viewerContext, id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
        val recipient = client.users.findById(viewerContext, req.recipientId).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Recipient not found")

        val friendship = client.friendships.create {
            this.requesterId = requester.id
            this.recipientId = recipient.id
            status = FriendshipStatus.PENDING
        }.saveAndLoad(viewerContext).getOrThrow()
        return friendship.toResponse()
    }

    @PostMapping("/friendships/{id}/accept")
    fun accept(@PathVariable id: Int): FriendshipResponse {
        val viewerContext = auth.viewerContext()
        // No pre-load: `update(id)` does its own internal byId.
        // Missing-row → Failed(EntTargetAbsentException) → 404 via ErrorHandler.
        val updated = client.friendships.update(id) {
            status = FriendshipStatus.ACCEPTED
        }.saveAndLoad(viewerContext).getOrThrow()
        return updated.toResponse()
    }

    @GetMapping("/users/{id}/friends")
    fun listFriends(@PathVariable id: UUID): List<UserResponse> {
        val viewerContext = auth.viewerContext()
        client.users.findById(viewerContext, id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        val accepted = client.friendships.query {
            where(
                (Friendship.requesterId eq id) or (Friendship.recipientId eq id),
            )
            where(Friendship.status eq FriendshipStatus.ACCEPTED)
        }.all(viewerContext).getOrThrow()

        val friendIds = accepted.map { f ->
            if (f.requesterId == id) f.recipientId else f.requesterId
        }

        return friendIds.mapNotNull { client.users.findById(viewerContext, it).getOrThrow() }
            .map { it.toResponse() }
    }

    @GetMapping("/users/{id}/friend-requests")
    fun listPendingRequests(@PathVariable id: UUID): List<FriendshipResponse> {
        val viewerContext = auth.viewerContext()
        client.users.findById(viewerContext, id).getOrThrow()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

        return client.friendships.query {
            where(Friendship.recipientId eq id)
            where(Friendship.status eq FriendshipStatus.PENDING)
        }.all(viewerContext).getOrThrow().map { it.toResponse() }
    }
}
