package example.spring.friendships

import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyDecision
import example.ent.Friendship
import example.ent.FriendshipCreatePrivacyContext
import example.ent.FriendshipCreatePrivacyRule
import example.ent.FriendshipLoadPrivacyContext
import example.ent.FriendshipLoadPrivacyRule
import example.ent.FriendshipPolicyScope

/**
 * Privacy is fail-closed, so friendships need explicit Allow rules. This demo's
 * friendship endpoints are open (create/update/delete derive from the create
 * rule), preserving the pre-fail-closed implicit access, now made explicit.
 */
object FriendshipPolicy : EntityPolicy<Friendship, FriendshipPolicyScope> {
    override fun configure(scope: FriendshipPolicyScope) = scope.run {
        privacy {
            load(AllowFriendshipRead())
            create(AllowFriendshipWrite())
            updateDerivesFromCreate()
            deleteDerivesFromCreate()
        }
    }
}

class AllowFriendshipRead : FriendshipLoadPrivacyRule {
    override fun run(ctx: FriendshipLoadPrivacyContext): PrivacyDecision = PrivacyDecision.Allow
}

class AllowFriendshipWrite : FriendshipCreatePrivacyRule {
    override fun run(ctx: FriendshipCreatePrivacyContext): PrivacyDecision = PrivacyDecision.Allow
}
