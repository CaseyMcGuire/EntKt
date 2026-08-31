package example.spring.users

import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import entkt.runtime.privacy.allowAll
import example.ent.ReadOnlyEntClient
import example.ent.User
import example.ent.UserDeleteRuleInput
import example.ent.UserDeletePrivacyRule
import example.ent.UserPolicyScope
import example.ent.UserUpdateRuleInput
import example.ent.UserUpdatePrivacyRule

object UserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            // Profiles are public and registration is open in this demo —
            // `allowAll` is the stock rule, so no per-type rule is needed.
            load(allowAll)
            create(allowAll)
            update(AllowSelfUpdate())
            delete(AllowSelfDelete())
        }
    }
}

/** Only the user themselves can update their profile. */
class AllowSelfUpdate : UserUpdatePrivacyRule {
    override fun run(
        context: PrivacyRuleContext<ReadOnlyEntClient>,
        item: UserUpdateRuleInput,
    ): PrivacyDecision {
        val viewer = context.viewerContext.viewer as? Viewer.User
            ?: return PrivacyDecision.Deny("authentication required")
        return if (viewer.id == item.before.id) PrivacyDecision.Allow
        else PrivacyDecision.Deny("can only update your own profile")
    }
}

/** Only the user themselves can delete their account. */
class AllowSelfDelete : UserDeletePrivacyRule {
    override fun run(
        context: PrivacyRuleContext<ReadOnlyEntClient>,
        item: UserDeleteRuleInput,
    ): PrivacyDecision {
        val viewer = context.viewerContext.viewer as? Viewer.User
            ?: return PrivacyDecision.Deny("authentication required")
        return if (viewer.id == item.entity.id) PrivacyDecision.Allow
        else PrivacyDecision.Deny("can only delete your own account")
    }
}
