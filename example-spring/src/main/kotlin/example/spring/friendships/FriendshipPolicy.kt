package example.spring.friendships

import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.allowAll
import example.ent.Friendship
import example.ent.FriendshipPolicyScope

/**
 * This demo's friendship endpoints are open: anyone may read or write them
 * (create/update/delete derive from create). Uses the stock `allowAll` rule.
 */
object FriendshipPolicy : EntityPolicy<Friendship, FriendshipPolicyScope> {
    override fun configure(scope: FriendshipPolicyScope) = scope.run {
        privacy {
            load(allowAll)
            create(allowAll)
            updateDerivesFromCreate()
            deleteDerivesFromCreate()
        }
    }
}
