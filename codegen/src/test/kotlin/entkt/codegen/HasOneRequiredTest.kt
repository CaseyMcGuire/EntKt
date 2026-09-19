@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.runtime.driver.RequiredOneConstraint
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HasOneRequiredTest {
    private class Owner : EntSchema("owners", clientName = "owners") {
        override fun id() = EntId.long()
        val profile by hasOne<Profile>().required()
        val preferences by hasOne<Preferences>().required()
        val optionalProfile by hasOne<Profile>()
    }

    private class Profile : EntSchema("profiles", clientName = "profiles") {
        override fun id() = EntId.long()
        val ownerKey by long("owner_ref")
        val owner by belongsTo<Owner>("owner_ref").field(ownerKey).unique().inverse(Owner::profile)
        val optionalOwner by belongsTo<Owner>("optional_owner_ref").nullable().unique()
            .inverse(Owner::optionalProfile)
    }

    private class Preferences : EntSchema("preferences", clientName = "preferences") {
        override fun id() = EntId.long()
        val owner by belongsTo<Owner>("owner_key").unique().inverse(Owner::preferences)
    }

    private fun inputs() = listOf(Owner(), Profile(), Preferences()).map(::SchemaInput)

    private val expected = listOf(
        RequiredOneConstraint("profile", "profiles", "owner_ref"),
        RequiredOneConstraint("preferences", "preferences", "owner_key"),
    )

    @Test
    fun `migration metadata adds constraints without adding owner columns`() {
        val schemas = buildEntitySchemas(inputs())
        val owner = schemas.single { it.table == "owners" }
        assertEquals(listOf("id"), owner.columns.map { it.name })
        assertEquals(expected, owner.requiredOneConstraints)
        assertEquals(emptyList(), schemas.single { it.table == "profiles" }.requiredOneConstraints)
    }

    @Test
    fun `compiled generated metadata matches the migration schema`() {
        val generated = EntGenerator("com.example.ent").generate(inputs())
        val application = SourceFile.kotlin(
            "Application.kt",
            """
            package com.example.app
            import com.example.ent.Owner
            import entkt.runtime.driver.RequiredOneConstraint

            fun constraints(): List<RequiredOneConstraint> = Owner.SCHEMA.requiredOneConstraints
            """.trimIndent(),
        )
        val result = compileSources(generated.toCompileTestSources() + application)
        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        assertEquals(
            expected,
            result.classLoader.loadClass("com.example.app.ApplicationKt").getMethod("constraints").invoke(null),
        )
    }

    private class MissingInverse : EntSchema("missing_inverse", clientName = "missingInverse") {
        override fun id() = EntId.long()
        val profile by hasOne<UnlinkedProfile>().required()
    }

    private class UnlinkedProfile : EntSchema("unlinked_profiles", clientName = "unlinkedProfiles") {
        override fun id() = EntId.long()
    }

    @Test
    fun `required hasOne cannot silently omit a missing inverse`() {
        val error = assertFailsWith<IllegalStateException> {
            buildEntitySchemas(listOf(MissingInverse(), UnlinkedProfile()).map(::SchemaInput))
        }
        assertContains(error.message!!, "no inverse belongsTo")
    }

    private class NonUniqueOwner : EntSchema("non_unique_owners", clientName = "nonUniqueOwners") {
        override fun id() = EntId.long()
        val profile by hasOne<NonUniqueProfile>().required()
    }

    private class NonUniqueProfile : EntSchema("non_unique_profiles", clientName = "nonUniqueProfiles") {
        override fun id() = EntId.long()
        val owner by belongsTo<NonUniqueOwner>("owner_id").inverse(NonUniqueOwner::profile)
    }

    @Test
    fun `required hasOne still requires a unique inverse FK`() {
        val error = assertFailsWith<IllegalStateException> {
            buildEntitySchemas(listOf(NonUniqueOwner(), NonUniqueProfile()).map(::SchemaInput))
        }
        assertContains(error.message!!, "must have .unique()")
    }
}
