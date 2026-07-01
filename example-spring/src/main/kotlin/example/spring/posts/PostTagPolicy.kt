package example.spring.posts

import entkt.runtime.privacy.EntityPolicy
import entkt.runtime.privacy.allowAll
import example.ent.PostTag
import example.ent.PostTagPolicyScope

/**
 * The post<->tag link table. Under fail-closed privacy, M2M traversal reads
 * junction rows and `addTag`/`removeTag` create/delete them directly, so the
 * junction needs explicit Allows. It carries no sensitive data of its own (the
 * endpoints Post/Tag hold the real rules), so it uses the stock `allowAll`.
 */
object PostTagPolicy : EntityPolicy<PostTag, PostTagPolicyScope> {
    override fun configure(scope: PostTagPolicyScope) = scope.run {
        privacy {
            load(allowAll)
            create(allowAll)
            deleteDerivesFromCreate()
        }
    }
}
