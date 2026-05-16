package entkt.runtime

import entkt.schema.FieldType
import entkt.schema.OnDelete
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the InMemoryDriver's exception classifier against every
 * IllegalStateException message its own validators throw. The
 * message-prefix coupling is brittle by design (any rename of the
 * message strings would silently break the classifier); these tests
 * are the regression net.
 */
class InMemoryDriverClassifyTest {

    private fun pkSchema(): EntitySchema = EntitySchema(
        table = "cls_pk",
        idColumn = "id",
        idStrategy = IdStrategy.EXPLICIT,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("label", FieldType.STRING, nullable = false),
        ),
        edges = emptyMap(),
    )

    private fun uniqueSchema(): EntitySchema = EntitySchema(
        table = "cls_unique",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("email", FieldType.STRING, nullable = false, unique = true),
        ),
        edges = emptyMap(),
    )

    private fun fkSchemas(): Pair<EntitySchema, EntitySchema> {
        val parents = EntitySchema(
            table = "cls_parents",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ),
            edges = emptyMap(),
        )
        val children = EntitySchema(
            table = "cls_children",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata(
                    "parent_id", FieldType.LONG, nullable = false,
                    references = ForeignKeyRef("cls_parents", "id", OnDelete.CASCADE),
                ),
            ),
            edges = emptyMap(),
        )
        return parents to children
    }

    private fun catchAndClassify(driver: InMemoryDriver, block: () -> Unit): EntError? {
        val ex = try {
            block(); return null
        } catch (e: Throwable) { e }
        return driver.classifyException(ex, entity = "TestEntity", operation = EntOperation.CREATE)
    }

    @Test
    fun `Primary key violation maps to ConstraintViolation with code 23505`() {
        val driver = InMemoryDriver().apply { register(pkSchema()) }
        driver.insert("cls_pk", mapOf("id" to 1L, "label" to "first"))

        val classified = catchAndClassify(driver) {
            driver.insert("cls_pk", mapOf("id" to 1L, "label" to "dup"))
        }
        assertTrue(classified is EntError.ConstraintViolation)
        val cv = classified as EntError.ConstraintViolation
        assertEquals("TestEntity", cv.entity)
        assertEquals(EntOperation.CREATE, cv.operation)
        assertEquals("primary_key", cv.constraint)
        assertEquals("23505", cv.code)
        assertTrue(cv.message.contains("Primary key violation"))
    }

    @Test
    fun `Unique violation maps to ConstraintViolation with code 23505 and constraint=unique`() {
        val driver = InMemoryDriver().apply { register(uniqueSchema()) }
        driver.insert("cls_unique", mapOf("email" to "a@b.com"))

        val classified = catchAndClassify(driver) {
            driver.insert("cls_unique", mapOf("email" to "a@b.com"))
        }
        assertTrue(classified is EntError.ConstraintViolation)
        val cv = classified as EntError.ConstraintViolation
        assertEquals("unique", cv.constraint)
        assertEquals("23505", cv.code)
    }

    @Test
    fun `Unique index violation maps to ConstraintViolation with constraint=unique_index`() {
        // updateMany batch-internal duplicate triggers the
        // "Unique index violation" message (different prefix from the
        // single-row composite path).
        val schema = EntitySchema(
            table = "cls_idx",
            idColumn = "id",
            idStrategy = IdStrategy.AUTO_LONG,
            columns = listOf(
                ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
                ColumnMetadata("a", FieldType.LONG, nullable = false),
                ColumnMetadata("b", FieldType.LONG, nullable = false),
            ),
            edges = emptyMap(),
            indexes = listOf(
                IndexMetadata(columns = listOf("a", "b"), unique = true, name = "cls_idx_ab"),
            ),
        )
        val driver = InMemoryDriver().apply { register(schema) }
        driver.insert("cls_idx", mapOf("a" to 1L, "b" to 10L))

        val classified = catchAndClassify(driver) {
            driver.insert("cls_idx", mapOf("a" to 1L, "b" to 10L))
        }
        assertTrue(classified is EntError.ConstraintViolation)
        val cv = classified as EntError.ConstraintViolation
        assertEquals("unique_index", cv.constraint)
        assertEquals("23505", cv.code)
    }

    @Test
    fun `FK violation on insert maps to ConstraintViolation with code 23503`() {
        val (parents, children) = fkSchemas()
        val driver = InMemoryDriver().apply {
            register(parents); register(children)
        }

        val classified = catchAndClassify(driver) {
            driver.insert("cls_children", mapOf("parent_id" to 999L))
        }
        assertTrue(classified is EntError.ConstraintViolation)
        val cv = classified as EntError.ConstraintViolation
        assertEquals("foreign_key", cv.constraint)
        assertEquals("23503", cv.code)
    }

    @Test
    fun `classifyDriverError returns ConstraintViolation when driver matches`() {
        val driver = InMemoryDriver().apply { register(pkSchema()) }
        driver.insert("cls_pk", mapOf("id" to 1L, "label" to "first"))

        val classified = try {
            driver.insert("cls_pk", mapOf("id" to 1L, "label" to "dup"))
            null
        } catch (e: Throwable) {
            classifyDriverError(driver, e, "TestEntity", EntOperation.CREATE)
        }
        assertTrue(classified is EntError.ConstraintViolation)
    }

    @Test
    fun `classifyDriverError falls back to DriverFailure for unrecognized throwables`() {
        val driver = InMemoryDriver()
        val arbitrary = RuntimeException("connection reset")
        val classified = classifyDriverError(driver, arbitrary, "TestEntity", EntOperation.UPDATE)
        assertTrue(classified is EntError.DriverFailure)
        val df = classified as EntError.DriverFailure
        assertEquals("TestEntity", df.entity)
        assertEquals(EntOperation.UPDATE, df.operation)
        assertEquals(arbitrary, df.cause)
    }

    @Test
    fun `classifyException returns null for non-IllegalStateException throwables`() {
        val driver = InMemoryDriver()
        // RuntimeException without the "FK violation" / "Unique
        // violation" message-prefix is not the driver's own constraint
        // throw — it should fall through to null so the caller's
        // DriverFailure fallback applies.
        val arbitrary = RuntimeException("connection reset")
        assertNull(driver.classifyException(arbitrary, "X", EntOperation.LOAD))
    }

    @Test
    fun `classifyException returns null for IllegalStateException without recognized prefix`() {
        val driver = InMemoryDriver()
        val unrecognized = IllegalStateException("some other internal invariant failed")
        assertNull(driver.classifyException(unrecognized, "X", EntOperation.LOAD))
    }
}
