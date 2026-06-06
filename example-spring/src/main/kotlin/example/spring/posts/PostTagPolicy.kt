package example.spring.posts

import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyDecision
import example.ent.PostTag
import example.ent.PostTagCreatePrivacyContext
import example.ent.PostTagCreatePrivacyRule
import example.ent.PostTagLoadPrivacyContext
import example.ent.PostTagLoadPrivacyRule
import example.ent.PostTagPolicyScope

/**
 * The post<->tag link table. Under fail-closed privacy, every entity the app
 * touches needs explicit Allow rules — and this demo manipulates the junction
 * directly: M2M traversal reads junction rows, `addTag` does
 * `postTags.create{}`, and `removeTag` does `postTags.deleteMany(...)`. The
 * junction carries no sensitive data of its own (the endpoints Post/Tag hold
 * the real rules), so all three are permitted; delete derives from create.
 */
object PostTagPolicy : EntityPolicy<PostTag, PostTagPolicyScope> {
    override fun configure(scope: PostTagPolicyScope) = scope.run {
        privacy {
            load(AllowLinkRead())
            create(AllowLinkWrite())
            deleteDerivesFromCreate()
        }
    }
}

class AllowLinkRead : PostTagLoadPrivacyRule {
    override fun run(ctx: PostTagLoadPrivacyContext): PrivacyDecision = PrivacyDecision.Allow
}

class AllowLinkWrite : PostTagCreatePrivacyRule {
    override fun run(ctx: PostTagCreatePrivacyContext): PrivacyDecision = PrivacyDecision.Allow
}
