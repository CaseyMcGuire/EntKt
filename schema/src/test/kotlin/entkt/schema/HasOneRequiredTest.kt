package entkt.schema

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HasOneRequiredTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
        val profile by hasOne<Profile>().required().comment("Required at commit")
        val optionalProfile by hasOne<Profile>()
    }

    private class Profile : EntSchema("profiles", clientName = "profiles") {
        override fun id() = EntId.long()
        val owner by belongsTo<Owner>("owner_id").unique().inverse(Owner::profile)
        val optionalOwner by belongsTo<Owner>("optional_owner_id").nullable().unique()
            .inverse(Owner::optionalProfile)
    }

    private fun owner(): Owner {
        val owner = Owner()
        val schemas = listOf(owner, Profile())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        return owner
    }

    @Test
    fun `hasOne stays optional unless explicitly required`() {
        val edges = owner().edges().associateBy { it.name }
        assertTrue((edges.getValue("profile").kind as EdgeKind.HasOne).required)
        assertFalse((edges.getValue("optionalProfile").kind as EdgeKind.HasOne).required)
        assertEquals("Required at commit", edges.getValue("profile").comment)
    }

    @Test
    fun `required cannot change after finalization`() {
        val owner = owner()
        val error = assertFailsWith<IllegalStateException> { owner.optionalProfile.required() }
        assertContains(error.message!!, "cannot be modified after schema finalization")
    }
}
