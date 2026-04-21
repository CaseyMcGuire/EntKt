package example.spring

import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyDecision
import entkt.runtime.Viewer
import example.ent.PostCreatePrivacyContext
import example.ent.PostCreatePrivacyRule
import example.ent.PostDeletePrivacyContext
import example.ent.PostDeletePrivacyRule
import example.ent.PostLoadPrivacyContext
import example.ent.PostLoadPrivacyRule
import example.ent.PostPolicyScope
import example.ent.PostUpdatePrivacyContext
import example.ent.PostUpdatePrivacyRule
import example.ent.Post

object PostPolicy : EntityPolicy<Post, PostPolicyScope> {
    override fun configure(scope: PostPolicyScope) = scope.run {
        privacy {
            load(AllowPublishedPosts(), AllowAuthorLoad())
            create(RequireAuthToCreate())
            update(AllowAuthorUpdate())
            delete(AllowAuthorDelete())
        }
    }
}

/** Published posts are visible to everyone. */
class AllowPublishedPosts : PostLoadPrivacyRule {
    override fun run(ctx: PostLoadPrivacyContext): PrivacyDecision =
        if (ctx.entity.published) PrivacyDecision.Allow
        else PrivacyDecision.Continue
}

/** Unpublished posts are visible to their author. */
class AllowAuthorLoad : PostLoadPrivacyRule {
    override fun run(ctx: PostLoadPrivacyContext): PrivacyDecision {
        val viewer = ctx.privacy.viewer as? Viewer.User ?: return PrivacyDecision.Continue
        return if (viewer.id == ctx.entity.authorId) PrivacyDecision.Allow
        else PrivacyDecision.Continue
    }
}

/** Must be authenticated to create posts. */
class RequireAuthToCreate : PostCreatePrivacyRule {
    override fun run(ctx: PostCreatePrivacyContext): PrivacyDecision =
        if (ctx.privacy.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
        else PrivacyDecision.Continue
}

/** Only the author can update their post. */
class AllowAuthorUpdate : PostUpdatePrivacyRule {
    override fun run(ctx: PostUpdatePrivacyContext): PrivacyDecision {
        val viewer = ctx.privacy.viewer as? Viewer.User
            ?: return PrivacyDecision.Deny("authentication required")
        return if (viewer.id == ctx.before.authorId) PrivacyDecision.Allow
        else PrivacyDecision.Deny("only the author can update this post")
    }
}

/** Only the author can delete their post. */
class AllowAuthorDelete : PostDeletePrivacyRule {
    override fun run(ctx: PostDeletePrivacyContext): PrivacyDecision {
        val viewer = ctx.privacy.viewer as? Viewer.User
            ?: return PrivacyDecision.Deny("authentication required")
        return if (viewer.id == ctx.entity.authorId) PrivacyDecision.Allow
        else PrivacyDecision.Deny("only the author can delete this post")
    }
}
