package example.spring.tags

import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.allowAll
import example.ent.Tag
import example.ent.TagPolicyScope

/**
 * Tags are open public labels in this demo: anyone may read, create, or delete
 * them. `allowAll` is the stock rule shared by every entity — no per-type
 * "allow everything" rule needed.
 */
object TagPolicy : EntityPolicy<Tag, TagPolicyScope> {
    override fun configure(scope: TagPolicyScope) = scope.run {
        privacy {
            load(allowAll)
            create(allowAll)
            deleteDerivesFromCreate()
        }
    }
}
