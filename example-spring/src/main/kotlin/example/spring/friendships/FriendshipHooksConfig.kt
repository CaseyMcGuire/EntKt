package example.spring.friendships

import entkt.runtime.mutation.orElse
import entkt.runtime.hook.EntityHooks
import example.ent.Friendship
import example.ent.FriendshipBeforeCreateState
import example.ent.FriendshipBeforeSaveState
import example.ent.FriendshipBeforeUpdateState
import example.schema.FriendshipStatus
import org.springframework.stereotype.Component

@Component
class FriendshipHooksConfig {

    fun apply(
        hooks: EntityHooks<
            FriendshipBeforeSaveState,
            FriendshipBeforeCreateState,
            FriendshipBeforeUpdateState,
            Friendship,
        >,
    ) {
        hooks.beforeCreate(::requireValidParticipants)
        hooks.beforeCreate(::forbidDuplicateRequest)
        hooks.beforeUpdate(::enforceStatusTransition)
    }

    fun requireValidParticipants(state: FriendshipBeforeCreateState): FriendshipBeforeCreateState {
        val requesterId = checkNotNull(state.requesterId.orElse(null))
        val recipientId = checkNotNull(state.recipientId.orElse(null))
        require(requesterId != recipientId) { "Cannot friend yourself" }
        return state
    }

    fun forbidDuplicateRequest(state: FriendshipBeforeCreateState): FriendshipBeforeCreateState {
        val requesterId = checkNotNull(state.requesterId.orElse(null))
        val recipientId = checkNotNull(state.recipientId.orElse(null))
        val existing = state.client.friendships.query {
            where(
                ((Friendship.requesterId eq requesterId) and (Friendship.recipientId eq recipientId))
                    or ((Friendship.requesterId eq recipientId) and (Friendship.recipientId eq requesterId)),
            )
        }.all(state.viewerContext).getOrThrow()
        require(existing.isEmpty()) { "Friend request already exists" }
        return state
    }

    fun enforceStatusTransition(state: FriendshipBeforeUpdateState): FriendshipBeforeUpdateState {
        val oldStatus = state.before.status
        val newStatus = state.status.orElse(oldStatus)
        if (oldStatus != newStatus) {
            require(oldStatus == FriendshipStatus.PENDING && newStatus == FriendshipStatus.ACCEPTED) {
                "Can only transition from PENDING to ACCEPTED"
            }
        }
        return state
    }
}
