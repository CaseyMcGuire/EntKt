package example.spring.tags

import entkt.runtime.EntityPolicy
import entkt.runtime.PrivacyDecision
import example.ent.Tag
import example.ent.TagCreatePrivacyContext
import example.ent.TagCreatePrivacyRule
import example.ent.TagLoadPrivacyContext
import example.ent.TagLoadPrivacyRule
import example.ent.TagPolicyScope

/**
 * Privacy is fail-closed, so every entity the app uses needs explicit Allow
 * rules. Tags are open public labels in this demo: anyone may read, create, or
 * delete them (preserving the pre-fail-closed implicit access, now explicit).
 */
object TagPolicy : EntityPolicy<Tag, TagPolicyScope> {
    override fun configure(scope: TagPolicyScope) = scope.run {
        privacy {
            load(AllowPublicTagRead())
            create(AllowPublicTagWrite())
            deleteDerivesFromCreate()
        }
    }
}

class AllowPublicTagRead : TagLoadPrivacyRule {
    override fun run(ctx: TagLoadPrivacyContext): PrivacyDecision = PrivacyDecision.Allow
}

class AllowPublicTagWrite : TagCreatePrivacyRule {
    override fun run(ctx: TagCreatePrivacyContext): PrivacyDecision = PrivacyDecision.Allow
}
