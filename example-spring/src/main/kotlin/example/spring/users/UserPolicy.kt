package example.spring.users

import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyDecision
import entkt.runtime.Viewer
import example.ent.User
import example.ent.UserCreatePrivacyContext
import example.ent.UserCreatePrivacyRule
import example.ent.UserDeletePrivacyContext
import example.ent.UserDeletePrivacyRule
import example.ent.UserLoadPrivacyContext
import example.ent.UserLoadPrivacyRule
import example.ent.UserPolicyScope
import example.ent.UserUpdatePrivacyContext
import example.ent.UserUpdatePrivacyRule

object UserPolicy : EntityPolicy<User, UserPolicyScope> {
    override fun configure(scope: UserPolicyScope) = scope.run {
        privacy {
            // Privacy is fail-closed, so the previously-implicit public access
            // to user profiles and registration is now spelled out explicitly.
            load(AllowPublicRead())
            create(AllowOpenRegistration())
            update(AllowSelfUpdate())
            delete(AllowSelfDelete())
        }
    }
}

/** User profiles are public in this demo. */
class AllowPublicRead : UserLoadPrivacyRule {
    override fun run(ctx: UserLoadPrivacyContext): PrivacyDecision = PrivacyDecision.Allow
}

/** Anyone can register a new user in this demo. */
class AllowOpenRegistration : UserCreatePrivacyRule {
    override fun run(ctx: UserCreatePrivacyContext): PrivacyDecision = PrivacyDecision.Allow
}

/** Only the user themselves can update their profile. */
class AllowSelfUpdate : UserUpdatePrivacyRule {
    override fun run(ctx: UserUpdatePrivacyContext): PrivacyDecision {
        val viewer = ctx.privacy.viewer as? Viewer.User
            ?: return PrivacyDecision.Deny("authentication required")
        return if (viewer.id == ctx.before.id) PrivacyDecision.Allow
        else PrivacyDecision.Deny("can only update your own profile")
    }
}

/** Only the user themselves can delete their account. */
class AllowSelfDelete : UserDeletePrivacyRule {
    override fun run(ctx: UserDeletePrivacyContext): PrivacyDecision {
        val viewer = ctx.privacy.viewer as? Viewer.User
            ?: return PrivacyDecision.Deny("authentication required")
        return if (viewer.id == ctx.entity.id) PrivacyDecision.Allow
        else PrivacyDecision.Deny("can only delete your own account")
    }
}
