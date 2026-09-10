package entkt.schema

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IndexDelegateBindingTest {
    private fun finalized(vararg schemas: EntSchema) {
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
    }

    @Test
    fun `by captures the API name without changing the storage definition`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val tenantId by long("tenant_ref")
            val position by int("sort_order")
            val byTenantAndPosition by index("uq_records_tenant_position", tenantId, position).unique()
        }

        val schema = Record()
        val index = schema.indexes().single()
        assertEquals("byTenantAndPosition", index.declarationName)
        assertEquals("uq_records_tenant_position", index.name)
        assertEquals(listOf("tenant_ref", "sort_order"), index.fields)
        assertTrue(index.unique)
        assertSame(schema.byTenantAndPosition, schema.byTenantAndPosition)
        finalized(schema)
        assertEquals(index, schema.indexes().single())
    }

    @Test
    fun `ordinary indexes remain unnamed including partial indexes`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val status by string("status")
            val byStatus = index("idx_status", status)
            val byActiveStatus = index("idx_active_status", status).where("active = true")
        }

        val schema = Record()
        finalized(schema)
        assertTrue(schema.indexes().all { it.declarationName == null })
        assertEquals("active = true", schema.byActiveStatus.build().where)
    }

    @Test
    fun `named indexes can use implicit FK handles`() {
        class Parent : EntSchema("parents", clientName = "parents") {
            override fun id() = EntId.long()
        }
        class Child : EntSchema("children", clientName = "children") {
            override fun id() = EntId.long()
            val parent by belongsTo<Parent>("parent")
            val position by int("position")
            val byParentAndPosition by index("uq_parent_position", parent.fk, position).unique()
        }

        val child = Child()
        finalized(Parent(), child)
        assertEquals(listOf("parent_id", "position"), child.indexes().single().fields)
        assertEquals("byParentAndPosition", child.indexes().single().declarationName)
    }

    @Test
    fun `named index builder cannot be rebound or modified after finalization`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val code by string("code")
            val byCode by index("idx_code", code)
        }

        val schema = Record()
        finalized(schema)
        assertFailsWith<IllegalStateException> { schema.byCode.unique() }
        assertFailsWith<IllegalStateException> { schema.byCode.where("true") }
        assertFailsWith<IllegalStateException> { schema.byCode.provideDelegate(schema, Record::byCode) }
    }

    @Test
    fun `one index cannot bind two declaration names`() {
        val error = assertFailsWith<IllegalStateException> {
            object : EntSchema("records", clientName = "records") {
                override fun id() = EntId.long()
                val code by string("code")
                val byCode by index("idx_code", code)
                val alsoByCode by byCode
            }
        }
        assertContains(error.message!!, "already bound to declaration 'byCode'")
    }

    @Test
    fun `private delegated indexes cannot create public accessors`() {
        val error = assertFailsWith<IllegalStateException> {
            object : EntSchema("records", clientName = "records") {
                override fun id() = EntId.long()
                val code by string("code")
                private val byCode by index("idx_code", code)
            }
        }
        assertContains(error.message!!, "must come from a public `val`")
    }

    @Test
    fun `index names use the same API identifier validation as other declarations`() {
        val error = assertFailsWith<IllegalArgumentException> {
            object : EntSchema("records", clientName = "records") {
                override fun id() = EntId.long()
                val code by string("code")
                val `by_code` by index("idx_code", code)
            }
        }
        assertContains(error.message!!, "not a valid generated API name")
    }

    @Test
    fun `an unrelated holder cannot name a schema's index`() {
        class Holder(index: IndexBuilder) {
            val byCode by index
        }
        val error = assertFailsWith<IllegalStateException> {
            object : EntSchema("records", clientName = "records") {
                override fun id() = EntId.long()
                val code by string("code")
                val holder = Holder(index("idx_code", code))
            }
        }
        assertContains(error.message!!, "neither the declaring schema nor a mixin it includes")
    }

    private class Codes(scope: EntMixin.Scope) : EntMixin(scope) {
        val code by string("code")
        val byCode by index("idx_code", code)
    }

    @Test
    fun `mixins contribute named indexes without a prefix or field-namespace conflicts`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val codes = include(::Codes)
            // This unindexed field lives on the entity, not in its indexes namespace.
            val byCode by string("unrelated_column")
        }

        val schema = Record()
        finalized(schema)
        assertEquals("byCode", schema.indexes().single().declarationName)
    }

    @Test
    fun `host and mixin indexes cannot claim the same accessor name`() {
        class Record : EntSchema("records", clientName = "records") {
            override fun id() = EntId.long()
            val codes = include(::Codes)
            val title by string("title")
            val byCode by index("idx_title", title)
        }

        val error = assertFailsWith<IllegalStateException> { finalized(Record()) }
        assertContains(error.message!!, "index declaration name 'byCode' is claimed by multiple indexes")
    }

    private open class IndexBase : EntSchema("records", clientName = "records") {
        override fun id() = EntId.long()
        val codes = include(::Codes)
        val byUniqueCode by index("uq_code", codes.code).unique()
    }

    private class InheritedIndex : IndexBase()

    @Test
    fun `a named index inherited from a base schema is rejected`() {
        val error = assertFailsWith<IllegalStateException> { finalized(InheritedIndex()) }
        assertContains(error.message!!, "index declaration 'byUniqueCode' is not declared on 'InheritedIndex'")
    }

    private open class IndexMixinBase(scope: EntMixin.Scope) : EntMixin(scope) {
        val codes = include(::Codes)
        val byUniqueCode by index("uq_code", codes.code).unique()
    }

    private class InheritedIndexMixin(scope: EntMixin.Scope) : IndexMixinBase(scope)

    @Test
    fun `a named index inherited from a base mixin is rejected`() {
        val error = assertFailsWith<IllegalStateException> {
            object : EntSchema("records", clientName = "records") {
                override fun id() = EntId.long()
                val codes = include(::InheritedIndexMixin)
            }
        }
        assertContains(error.message!!, "declaration 'byUniqueCode' is not declared on 'InheritedIndexMixin'")
    }
}
