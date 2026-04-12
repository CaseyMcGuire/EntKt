package example.spring

import example.ent.Friendship
import example.ent.FriendshipHooks
import org.springframework.stereotype.Component

@Component
class FriendshipHooksConfig {
    fun apply(hooks: FriendshipHooks) {
        hooks.beforeCreate {
            val requesterId = it.requesterId
                ?: throw IllegalStateException("requester is required")
            val recipientId = it.recipientId
                ?: throw IllegalStateException("recipient is required")

            require(requesterId != recipientId) { "Cannot friend yourself" }

            val existing = it.client.friendships.query {
                where(
                    ((Friendship.requesterId eq requesterId) and (Friendship.recipientId eq recipientId))
                        or ((Friendship.requesterId eq recipientId) and (Friendship.recipientId eq requesterId)),
                )
            }.all()
            require(existing.isEmpty()) { "Friend request already exists" }
        }

        hooks.beforeUpdate {
            val oldStatus = it.entity.status
            val newStatus = it.status ?: oldStatus
            if (oldStatus != newStatus) {
                require(oldStatus == "PENDING" && newStatus == "ACCEPTED") {
                    "Can only transition from PENDING to ACCEPTED"
                }
            }
        }
    }
}
