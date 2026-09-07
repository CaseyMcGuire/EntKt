package example.spring.posts

import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.PrivacyDecision
import entkt.runtime.privacy.PrivacyRuleContext
import entkt.runtime.privacy.Viewer
import example.ent.ReadOnlyEntClient
import example.ent.PostCreateRuleInput
import example.ent.PostCreatePrivacyRule
import example.ent.PostDeleteRuleInput
import example.ent.PostDeletePrivacyRule
import example.ent.PostLoadPrivacyRule
import example.ent.PostPolicyScope
import example.ent.PostUpdateRuleInput
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
    override fun run(
        context: PrivacyRuleContext<ReadOnlyEntClient>,
        item: Post,
    ): PrivacyDecision =
        if (item.published) PrivacyDecision.Allow
        else PrivacyDecision.Continue
}

/** Unpublished posts are visible to their author. */
class AllowAuthorLoad : PostLoadPrivacyRule {
    override fun run(
        context: PrivacyRuleContext<ReadOnlyEntClient>,
        item: Post,
    ): PrivacyDecision {
        val viewer = context.viewerContext.viewer as? Viewer.User ?: return PrivacyDecision.Continue
        return if (viewer.id == item.authorId) PrivacyDecision.Allow
        else PrivacyDecision.Continue
    }
}

/** Any authenticated user can create posts; anonymous callers are denied. */
class RequireAuthToCreate : PostCreatePrivacyRule {
    override fun run(
        context: PrivacyRuleContext<ReadOnlyEntClient>,
        item: PostCreateRuleInput,
    ): PrivacyDecision =
        if (context.viewerContext.viewer is Viewer.Anonymous) PrivacyDecision.Deny("authentication required")
        else PrivacyDecision.Allow
}

/** Only the author can update their post. */
class AllowAuthorUpdate : PostUpdatePrivacyRule {
    override fun run(
        context: PrivacyRuleContext<ReadOnlyEntClient>,
        item: PostUpdateRuleInput,
    ): PrivacyDecision {
        val viewer = context.viewerContext.viewer as? Viewer.User
            ?: return PrivacyDecision.Deny("authentication required")
        return if (viewer.id == item.before.authorId) PrivacyDecision.Allow
        else PrivacyDecision.Deny("only the author can update this post")
    }
}

/** Only the author can delete their post. */
class AllowAuthorDelete : PostDeletePrivacyRule {
    override fun run(
        context: PrivacyRuleContext<ReadOnlyEntClient>,
        item: PostDeleteRuleInput,
    ): PrivacyDecision {
        val viewer = context.viewerContext.viewer as? Viewer.User
            ?: return PrivacyDecision.Deny("authentication required")
        return if (viewer.id == item.entity.authorId) PrivacyDecision.Allow
        else PrivacyDecision.Deny("only the author can delete this post")
    }
}
