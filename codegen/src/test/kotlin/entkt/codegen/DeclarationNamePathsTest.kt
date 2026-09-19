package entkt.codegen

import entkt.codegen.entity.EntityGenerator
import entkt.codegen.query.EntityDescriptorGenerator
import entkt.codegen.query.QueryGenerator
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class PathUser : EntSchema("path_users", clientName = "pathUsers") {
    override fun id() = EntId.long()
    val directories by hasMany<PathDirectory>()
}

/**
 * The FK column (`legacy_owner_id`) and relationship name (`curator`)
 * differ, so confusing physical storage with relationship identity is visible.
 */
private class PathDirectory : EntSchema("path_dirs", clientName = "pathDirs") {
    override fun id() = EntId.long()
    val curator by belongsTo<PathUser>("legacy_owner_id").nullable().inverse(PathUser::directories)
}

/**
 * Relationship lookup, predicate dispatch, and caller-facing paths all use
 * the declaration name. Only join metadata should use the physical FK column.
 *
 * See `docs/02-schema.md#names`.
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

    private fun generatedQueryAndDescriptors(
        user: PathUser,
        dir: PathDirectory,
    ): String {
        val names = names(user, dir)
        return buildString {
            append(
                QueryGenerator("com.example.ent")
                    .generate("PathDirectory", dir, names),
            )
            EntityDescriptorGenerator("com.example.ent")
                .generate("PathDirectory", dir, names)
                .forEach(::append)
        }
    }

    @Test
    fun `caller-facing traversal paths carry the edge declaration name`() {
        val (user, dir) = fixture()
        val query = generatedQueryAndDescriptors(user, dir).replace("\\s+".toRegex(), " ")

        // Runtime paths derive their caller-facing EdgeStep from the
        // typed mapping's declaration name.
        assertTrue(
            """override val name: String = "curator""" in query,
            "the captured edge mapping must use the declaration name\n$query",
        )
        assertTrue(
            "traversalQuery(PathDirectoryCuratorEdgeDescriptor, \"queryCurator()\")" in query,
            "traversal must retain the declaration-derived typed mapping\n$query",
        )
        assertFalse(
            """override val name: String = "legacy_owner_id""" in query,
            "storage edge name must not become the caller-facing mapping name\n$query",
        )
    }

    @Test
    fun `predicate dispatch and edge refs use the relationship name`() {
        val (user, dir) = fixture()
        val entity = EntityGenerator("com.example.ent")
            .generate("PathDirectory", dir, names(user, dir))
            .toString().replace("\\s+".toRegex(), " ")

        assertTrue(
            """EdgeRef<PathDirectory, PathUser, PathUserQueryScope> = EdgeRef("curator")""" in entity,
            "companion EdgeRef must use the relationship name\n$entity",
        )
        assertTrue(
            "public val curator:" in entity,
            "the companion property itself is the declaration name\n$entity",
        )
    }

    @Test
    fun `edge-predicate interception uses relationship names while joins use columns`() {
        val (user, dir) = fixture()
        val query = generatedQueryAndDescriptors(user, dir).replace("\\s+".toRegex(), " ")

        assertTrue(
            """"curator" to PathDirectoryCuratorEdgeDescriptor""" in query,
            "edge-predicate dispatch must key on the relationship name\n$query",
        )
        assertTrue(
            """sourceColumn = "legacy_owner_id"""" in query,
            "joins must retain the literal FK column\n$query",
        )
        assertTrue(
            """override val name: String = "curator""" in query,
            "runtime paths should read the declaration name from the mapping\n$query",
        )
    }
}
