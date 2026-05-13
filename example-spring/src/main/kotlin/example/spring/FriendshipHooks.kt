package example.spring

import entkt.runtime.orElse
import example.ent.Friendship
import example.ent.FriendshipCreateHookContext
import example.ent.FriendshipHooks
import example.ent.FriendshipUpdateHookContext
import example.schema.FriendshipStatus
import org.springframework.stereotype.Component

@Component
class FriendshipHooksConfig {

    fun apply(hooks: FriendshipHooks) {
        hooks.beforeCreate(::requireValidParticipants)
        hooks.beforeCreate(::forbidDuplicateRequest)
        hooks.beforeUpdate(::enforceStatusTransition)
    }

    fun requireValidParticipants(ctx: FriendshipCreateHookContext) {
        // Required FK getters throw on unassigned reads, so no explicit
        // null guard is needed here.
        require(ctx.mutation.requesterId != ctx.mutation.recipientId) { "Cannot friend yourself" }
    }

    fun forbidDuplicateRequest(ctx: FriendshipCreateHookContext) {
        val requesterId = ctx.mutation.requesterId
        val recipientId = ctx.mutation.recipientId
        val existing = ctx.client.friendships.query {
            where(
                ((Friendship.requesterId eq requesterId) and (Friendship.recipientId eq recipientId))
                    or ((Friendship.requesterId eq recipientId) and (Friendship.recipientId eq requesterId)),
            )
        }.all()
        require(existing.isEmpty()) { "Friend request already exists" }
    }

    fun enforceStatusTransition(ctx: FriendshipUpdateHookContext) {
        val oldStatus = ctx.before.status
        // Read pending state from the patch (explicit Set / Unset),
        // not from the mutation getter — the latter throws on untouched
        // fields per the id-based update root contract.
        val newStatus = ctx.patch.status.orElse(oldStatus)
        if (oldStatus != newStatus) {
            require(oldStatus == FriendshipStatus.PENDING && newStatus == FriendshipStatus.ACCEPTED) {
                "Can only transition from PENDING to ACCEPTED"
            }
        }
    }
}
