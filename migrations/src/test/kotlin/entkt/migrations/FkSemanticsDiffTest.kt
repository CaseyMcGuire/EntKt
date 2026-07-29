package entkt.migrations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The differ must hold introspected FKs to the same standard the
 * auto-DDL guard applies: a hand-written constraint with NOT VALID,
 * DEFERRABLE, MATCH FULL, a non-default ON UPDATE, or ON DELETE
 * SET DEFAULT is not the FK entkt would create, and comparing it equal
 * would let `migrate validate` report no drift for rules nobody
 * declared. Immediate RESTRICT and NO ACTION stay equivalent — the two
 * behave identically for the non-deferred constraints entkt creates.
 */
class FkSemanticsDiffTest {

    private val differ = SchemaDiffer()

    private fun postsTable(fk: NormalizedForeignKey) = NormalizedTable(
        name = "posts",
        columns = listOf(
            NormalizedColumn("id", "serial", nullable = false, primaryKey = true),
            NormalizedColumn("author_id", "integer", nullable = false, primaryKey = false),
        ),
        indexes = emptyList(),
        foreignKeys = listOf(fk),
    )

    private val desiredFk = NormalizedForeignKey(
        columns = listOf("author_id"),
        targetTable = "users",
        targetColumns = listOf("id"),
        columnNullable = false,
        onDelete = FkAction.RESTRICT,
    )

    private fun introspectedFk(
        onDelete: FkAction = FkAction.RESTRICT,
        onUpdate: FkAction = FkAction.NO_ACTION,
        matchType: FkMatchType = FkMatchType.SIMPLE,
        deferrable: Boolean = false,
        initiallyDeferred: Boolean = false,
        validated: Boolean = true,
        columns: List<String> = listOf("author_id"),
        targetColumns: List<String> = listOf("id"),
    ) = NormalizedForeignKey(
        columns = columns,
        targetTable = "users",
        targetColumns = targetColumns,
        columnNullable = false,
        constraintName = "fk_posts_author_id",
        onDelete = onDelete,
        onUpdate = onUpdate,
        matchType = matchType,
        deferrable = deferrable,
        initiallyDeferred = initiallyDeferred,
        validated = validated,
    )

    private fun diff(current: NormalizedForeignKey): DiffResult = differ.diff(
        NormalizedSchema(mapOf("posts" to postsTable(desiredFk))),
        NormalizedSchema(mapOf("posts" to postsTable(current))),
    )

    private fun assertDropAndReAdd(result: DiffResult) {
        assertEquals(1, result.manual.filterIsInstance<MigrationOp.DropForeignKey>().size, "manual drop expected")
        assertEquals(1, result.ops.filterIsInstance<MigrationOp.AddForeignKey>().size, "auto re-add expected")
    }

    private fun assertNoDrift(result: DiffResult) {
        assertTrue(result.ops.isEmpty(), "no auto ops expected; got ${result.ops}")
        assertTrue(result.manual.isEmpty(), "no manual ops expected; got ${result.manual}")
    }

    @Test
    fun `matching immediate constraint reports no drift`() {
        assertNoDrift(diff(introspectedFk(onDelete = FkAction.RESTRICT)))
    }

    @Test
    fun `NO ACTION compares equal to the RESTRICT entkt renders`() {
        assertNoDrift(diff(introspectedFk(onDelete = FkAction.NO_ACTION)))
    }

    @Test
    fun `NOT VALID constraint is drift`() {
        assertDropAndReAdd(diff(introspectedFk(validated = false)))
    }

    @Test
    fun `ON UPDATE CASCADE is drift`() {
        assertDropAndReAdd(diff(introspectedFk(onUpdate = FkAction.CASCADE)))
    }

    @Test
    fun `MATCH FULL is drift`() {
        assertDropAndReAdd(diff(introspectedFk(matchType = FkMatchType.FULL)))
    }

    @Test
    fun `DEFERRABLE constraint is drift`() {
        assertDropAndReAdd(diff(introspectedFk(deferrable = true)))
    }

    @Test
    fun `ON DELETE SET DEFAULT never collapses into an inferred action`() {
        // Nullable FK: the desired side resolves to SET NULL. Before
        // the exact-action model, SET DEFAULT introspected to null and
        // nullability inference made the two compare equal.
        val desired = desiredFk.copy(columnNullable = true, onDelete = FkAction.SET_NULL)
        val current = introspectedFk(onDelete = FkAction.SET_DEFAULT).copy(columnNullable = true)
        val result = differ.diff(
            NormalizedSchema(mapOf("posts" to postsTable(desired))),
            NormalizedSchema(mapOf("posts" to postsTable(current))),
        )
        assertDropAndReAdd(result)
    }

    @Test
    fun `composite FK sharing the first column never matches a single-column FK`() {
        val composite = introspectedFk(
            columns = listOf("author_id", "org_id"),
            targetColumns = listOf("id", "org_id"),
        )
        val result = diff(composite)

        // Different identity: the desired single-column FK is added,
        // the composite is dropped — not silently "matched" on the
        // shared first column.
        assertEquals(1, result.ops.filterIsInstance<MigrationOp.AddForeignKey>().size)
        val drops = result.manual.filterIsInstance<MigrationOp.DropForeignKey>()
        assertEquals(1, drops.size)
        assertEquals(listOf("author_id", "org_id"), drops[0].columns)
    }
}
