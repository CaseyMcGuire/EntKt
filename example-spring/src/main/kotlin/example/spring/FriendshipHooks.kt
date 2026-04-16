package example.spring

import example.ent.Friendship
import example.ent.FriendshipCreate
import example.ent.FriendshipHooks
import example.ent.FriendshipUpdate
import org.springframework.stereotype.Component

@Component
class FriendshipHooksConfig {

    fun apply(hooks: FriendshipHooks) {
        hooks.beforeCreate(::requireValidParticipants)
        hooks.beforeCreate(::forbidDuplicateRequest)
        hooks.beforeUpdate(::enforceStatusTransition)
    }

    fun requireValidParticipants(m: FriendshipCreate) {
        val requesterId = m.requesterId
            ?: throw IllegalStateException("requester is required")
        val recipientId = m.recipientId
            ?: throw IllegalStateException("recipient is required")
        require(requesterId != recipientId) { "Cannot friend yourself" }
    }

    fun forbidDuplicateRequest(m: FriendshipCreate) {
        val requesterId = m.requesterId
            ?: throw IllegalStateException("requester is required")
        val recipientId = m.recipientId
            ?: throw IllegalStateException("recipient is required")
        val existing = m.client.friendships.query {
            where(
                ((Friendship.requesterId eq requesterId) and (Friendship.recipientId eq recipientId))
                    or ((Friendship.requesterId eq recipientId) and (Friendship.recipientId eq requesterId)),
            )
        }.all()
        require(existing.isEmpty()) { "Friend request already exists" }
    }

    fun enforceStatusTransition(m: FriendshipUpdate) {
        val oldStatus = m.entity.status
        val newStatus = m.status ?: oldStatus
        if (oldStatus != newStatus) {
            require(oldStatus == "PENDING" && newStatus == "ACCEPTED") {
                "Can only transition from PENDING to ACCEPTED"
            }
        }
    }
}
