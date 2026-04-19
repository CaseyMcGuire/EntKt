package example.spring

import example.ent.Friendship
import example.schema.FriendshipStatus
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