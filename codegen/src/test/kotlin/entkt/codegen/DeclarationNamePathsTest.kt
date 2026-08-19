package entkt.codegen

import entkt.codegen.entity.EntityGenerator
import entkt.codegen.query.QueryGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class PathUser : EntSchema("path_users", clientName = "pathUsers") {
    override fun id() = EntId.long()
    val directories by hasMany<PathDirectory>("legacy_owner")
}

/**
 * Edge storage name (`legacy_owner`) and declaration name (`curator`)
 * disagree, so any place that emits one where the other belongs is
 * visible in the generated source.
 */
private class PathDirectory : EntSchema("path_dirs", clientName = "pathDirs") {
    override fun id() = EntId.long()
    val curator by belongsTo<PathUser>("legacy_owner").nullable().inverse(PathUser::directories)
}

/**
 * Pins the split between the two edge-name roles.
 *
 * `ResolvedQueryEdge` exposes both, and they are easy to confuse:
 * `name` is the storage identifier that keys driver edge metadata and
 * companion `EdgeRef` predicate dispatch, while `publicName` is the
 * Kotlin declaration that appears in caller-facing diagnostics. Emitting
 * either in the other's place produces code that still compiles — it
 * just reports the wrong name, or silently fails an edge lookup.
 *
 * See `docs/implemented-features/schema/schema-declaration-api-names.md`.
 */
class DeclarationNamePathsTest {

    private fun fixture(): Pair<PathUser, PathDirectory> {
        val user = PathUser()
        val dir = PathDirectory()
        val registry = mapOf<kotlin.reflect.KClass<out EntSchema>, EntSchema>(
            user::class to user,
            dir::class to dir,
        )
        user.finalize(registry)
        dir.finalize(registry)
        return user to dir
    }

    private fun names(user: PathUser, dir: PathDirectory) =
        mapOf<EntSchema, String>(user to "PathUser", dir to "PathDirectory")

    @Test
    fun `caller-facing traversal paths carry the edge declaration name`() {
        val (user, dir) = fixture()
        val query = QueryGenerator("com.example.ent")
            .generate("PathDirectory", dir, names(user, dir))
            .toString()

        // EdgeStep feeds InterceptorContext.path and the eager-denial
        // origin — both are read by application code.
        assertTrue(
            """EdgeStep(PathDirectory::class, "curator", PathUser::class)""" in query,
            "traversal EdgeStep must use the declaration name\n$query",
        )
        assertTrue(
            """seedEdgeTraversal(PathDirectory::class, "curator"""" in query,
            "traversal seeding must use the declaration name\n$query",
        )
        assertFalse(
            """EdgeStep(PathDirectory::class, "legacy_owner", PathUser::class)""" in query,
            "storage edge name must not reach a caller-facing path\n$query",
        )
    }

    @Test
    fun `predicate dispatch and edge refs stay on the storage name`() {
        val (user, dir) = fixture()
        val entity = EntityGenerator("com.example.ent")
            .generate("PathDirectory", dir, names(user, dir))
            .toString()

        // The companion EdgeRef is the driver's edge-lookup key, so it
        // must remain the storage identifier even though the Kotlin
        // property it hangs off is named `curator`.
        assertTrue(
            """EdgeRef<PathDirectory, PathUser, PathUserQuery> = EdgeRef("legacy_owner")""" in entity,
            "companion EdgeRef must keep the storage edge name\n$entity",
        )
        assertTrue(
            "public val curator:" in entity,
            "the companion property itself is the declaration name\n$entity",
        )
    }

    @Test
    fun `edge-predicate interception dispatches on storage but seeds the declaration`() {
        val (user, dir) = fixture()
        val query = QueryGenerator("com.example.ent")
            .generate("PathDirectory", dir, names(user, dir))
            .toString()

        // The `when` key is the companion EdgeRef's value, so it must
        // stay storage-keyed or the branch never matches...
        assertTrue(
            """"legacy_owner" -> {""" in query,
            "edge-predicate dispatch must key on the storage name\n$query",
        )
        // ...but what the target query is seeded with — and therefore
        // what its interceptors and denial paths report — is the
        // declaration name.
        assertTrue(
            """targetQ.seedEdgeTraversal(PathDirectory::class, "curator"""" in query,
            "edge-predicate seeding must use the declaration name\n$query",
        )
        assertFalse(
            """targetQ.seedEdgeTraversal(PathDirectory::class, predicate.edge""" in query,
            "the storage dispatch key must not leak into the seeded path\n$query",
        )
    }
}
