@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteCandidateCompileTest {
    private class Widget : EntSchema("widgets", clientName = "widgets") {
        override fun id() = EntId.int()
        val name by string("name")
    }

    private class WriteCandidate : EntSchema("write_candidates", clientName = "writeCandidates") {
        override fun id() = EntId.int()
    }

    private fun compile(sources: List<SourceFile>): JvmCompilationResult =
        KotlinCompilation().apply {
            this.sources = sources
            inheritClassPath = true
            kotlincArguments = listOf("-Xskip-metadata-version-check")
            jvmTarget = "17"
            messageOutputStream = java.io.OutputStream.nullOutputStream()
        }.compile()

    private fun mutationTypes(candidate: String): List<String> = listOf(
        "CreateMutationConverter<WidgetDraft, $candidate, Widget>",
        "CreateMutationOperation<Unit, WidgetDraft, $candidate, Widget, BeforeSave, BeforeCreate>",
        "CreateManyMutationOperation<Unit, WidgetDraft, $candidate, Widget, BeforeSave, BeforeCreate>",
        "UpdateMutationAdapter<WidgetUpdateDraft, Widget, PendingEdges, WidgetState, $candidate, BeforeUpdate>",
        "UpdateMutationOperation<Unit, WidgetUpdateDraft, Widget, PendingEdges, WidgetState, $candidate, BeforeSave, BeforeUpdate>",
        "DeleteMutationConverter<Widget, $candidate>",
        "DeleteMutationOperation<Unit, Widget, $candidate>",
        "DeleteManyMutationOperation<Unit, Widget, $candidate>",
        "DeleteRuleCandidate<Widget, $candidate>",
    )

    private fun compileTypes(types: List<String>): JvmCompilationResult = compile(
        listOf(
            SourceFile.kotlin(
                "CandidateBounds.kt",
                """
                @file:OptIn(entkt.query.EntktInternal::class)
                package com.example.app

                import entkt.runtime.entity.EntEntity
                import entkt.runtime.mutation.BeforeCreateHookState
                import entkt.runtime.mutation.BeforeSaveHookState
                import entkt.runtime.mutation.BeforeUpdateHookState
                import entkt.runtime.mutation.CreateMutationDraft
                import entkt.runtime.mutation.PreparedCreate
                import entkt.runtime.mutation.PreparedUpdateState
                import entkt.runtime.mutation.UpdateMutationDraft
                import entkt.runtime.mutation.UpdatePendingEdges
                import entkt.runtime.mutation.WriteCandidate
                import entkt.runtime.mutation.execution.*

                data class Widget(override val id: Int) : EntEntity.IntId
                data class Other(override val id: Int) : EntEntity.IntId
                class WidgetDraft : CreateMutationDraft<Widget>
                class WidgetUpdateDraft : UpdateMutationDraft<Widget>
                class WidgetState : PreparedUpdateState<Widget>
                class PendingEdges : UpdatePendingEdges<Widget>
                class WidgetCandidate : WriteCandidate<Widget>
                class OtherCandidate : WriteCandidate<Other>
                class BeforeSave : BeforeSaveHookState<Widget>
                class BeforeCreate : BeforeCreateHookState<Widget>
                class BeforeUpdate : BeforeUpdateHookState<Widget>

                ${types.mapIndexed { index, type -> "fun accept$index(value: $type) {}" }.joinToString("\n")}
                """.trimIndent(),
            ),
        ),
    )

    @Test
    fun `mutation types accept candidates for their entity without bounding rule clients`() {
        val result = compileTypes(
            mutationTypes("WidgetCandidate") + listOf(
                "PreparedCreate<WidgetCandidate>",
                "PreparedUpdate<WidgetState, WidgetCandidate>",
                "UpdatePreparation<WidgetState, WidgetCandidate>",
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `each mutation type rejects candidates for a different entity with the same ID type`() {
        for (type in mutationTypes("OtherCandidate")) {
            assertCandidateBoundFailure(type)
        }
    }

    @Test
    fun `each mutation type rejects candidates that do not implement the marker`() {
        val types = mutationTypes("String") + listOf(
            "PreparedCreate<String>",
            "PreparedUpdate<WidgetState, String>",
            "UpdatePreparation<WidgetState, String>",
        )
        for (type in types) {
            assertCandidateBoundFailure(type)
        }
    }

    private fun assertCandidateBoundFailure(type: String) {
        val result = compileTypes(listOf(type))

        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "Expected $type to reject the candidate:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("bound", ignoreCase = true) && result.messages.contains("WriteCandidate"),
            "Expected a WriteCandidate bound diagnostic for $type:\n${result.messages}",
        )
    }

    private fun compileOperationFactory(
        factory: String,
        mappingEntity: String = "Widget",
        ruleInputState: String = "WidgetState",
    ): JvmCompilationResult {
        val call = when (factory) {
            "create" -> """
                buildCreateOperations(
                    entity, runtime, combinedCreateConverter, privacy, validation,
                    beforeSave = emptyList(), beforeCreate = emptyList(), afterCreate = emptyList(),
                )
            """.trimIndent()
            "update" -> """
                buildUpdateOperation(
                    entity, runtime, privacy, validation,
                    ruleInput = { _: $ruleInputState -> Unit },
                    adapter = updateAdapter,
                    hookStateConverter = updateHooks,
                    beforeSave = emptyList(), beforeUpdate = emptyList(), afterUpdate = emptyList(),
                )
            """.trimIndent()
            "createMany" -> """
                buildCreateManyMutationOperation(
                    entity, runtime, createConverter, privacy, validation, createHooks,
                    beforeSave = emptyList(), beforeCreate = emptyList(), afterCreate = emptyList(),
                )
            """.trimIndent()
            "delete" -> """
                buildDeleteMutationOperation(
                    entity, deleteConverter, privacy, validation,
                    ruleInput = { _, _ -> Unit },
                    beforeDelete = emptyList(), afterDelete = emptyList(),
                )
            """.trimIndent()
            "deleteMany" -> """
                buildDeleteManyMutationOperation(
                    entity, deleteConverter, privacy, validation,
                    ruleInput = { _, _ -> Unit },
                    driver = driver, readExecutionHost = readHost,
                    beforeDelete = emptyList(), afterDelete = emptyList(),
                )
            """.trimIndent()
            else -> error("Unknown factory: $factory")
        }

        val resultType = when (factory) {
            "create" -> "CreateMutationOperations<Unit, WidgetCreateDraft, Widget>"
            "update" -> "MutationOperation<Unit, UpdateMutationInput<WidgetUpdateDraft>, Widget>"
            "createMany" -> "CreateManyMutationOperation<Unit, WidgetCreateDraft, WidgetCandidate, Widget, BeforeSave, BeforeCreate>"
            "delete" -> "DeleteMutationOperation<Unit, Widget, WidgetCandidate>"
            else -> "DeleteManyMutationOperation<Unit, Widget, WidgetCandidate>"
        }

        return compile(
            listOf(
                SourceFile.kotlin(
                    "OperationFactoryBounds.kt",
                    """
                    @file:OptIn(entkt.query.EntktInternal::class)
                    package com.example.app

                    import entkt.runtime.driver.DatabaseDriver
                    import entkt.runtime.query.execution.ReadQueryExecutionHost
                    import entkt.runtime.entity.EntEntity
                    import entkt.runtime.entity.EntityDescriptor
                    import entkt.runtime.mutation.*
                    import entkt.runtime.mutation.execution.*
                    import entkt.runtime.privacy.BatchPrivacyRule
                    import entkt.runtime.privacy.ResolvedEntityPrivacyConfig
                    import entkt.runtime.validation.BatchValidationRule
                    import entkt.runtime.validation.ResolvedEntityValidationConfig

                    data class Widget(override val id: Int) : EntEntity.IntId
                    data class Other(override val id: Int) : EntEntity.IntId
                    class WidgetCandidate : WriteCandidate<Widget>
                    class WidgetState : PreparedUpdateState<Widget>
                    class OtherState : PreparedUpdateState<Other>
                    class WidgetCreateDraft : CreateMutationDraft<Widget>
                    class WidgetUpdateDraft : UpdateMutationDraft<Widget>
                    class PendingEdges : UpdatePendingEdges<Widget>
                    class BeforeSave : BeforeSaveHookState<Widget>
                    class BeforeCreate : BeforeCreateHookState<Widget>
                    class BeforeUpdate : BeforeUpdateHookState<Widget>

                    abstract class CombinedCreateConverter :
                        CreateMutationConverter<WidgetCreateDraft, WidgetCandidate, Widget>,
                        CreateMutationHookStateConverter<WidgetCreateDraft, Widget, BeforeSave, BeforeCreate>

                    fun bind(
                        entity: EntityDescriptor<$mappingEntity, *>,
                        privacy: ResolvedEntityPrivacyConfig<
                            Nothing, BatchPrivacyRule<Unit, WidgetCandidate>,
                            BatchPrivacyRule<Unit, Unit>, BatchPrivacyRule<Unit, Unit>,
                        >,
                        validation: ResolvedEntityValidationConfig<
                            BatchValidationRule<Unit, WidgetCandidate>,
                            BatchValidationRule<Unit, Unit>, BatchValidationRule<Unit, Unit>,
                        >,
                        runtime: MutationRuntime,
                        combinedCreateConverter: CombinedCreateConverter,
                        updateAdapter: UpdateMutationAdapter<WidgetUpdateDraft, Widget, PendingEdges, WidgetState, WidgetCandidate, BeforeUpdate>,
                        updateHooks: UpdateMutationHookStateConverter<WidgetUpdateDraft, Widget, PendingEdges, BeforeSave, BeforeUpdate>,
                        createConverter: CreateMutationConverter<WidgetCreateDraft, WidgetCandidate, Widget>,
                        createHooks: CreateMutationHookStateConverter<WidgetCreateDraft, Widget, BeforeSave, BeforeCreate>,
                        deleteConverter: DeleteMutationConverter<Widget, WidgetCandidate>,
                        driver: DatabaseDriver,
                        readHost: ReadQueryExecutionHost,
                    ): $resultType = $call
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `operation factories infer matching entity and candidate types`() {
        for (factory in listOf("create", "createMany", "update", "delete", "deleteMany")) {
            val result = compileOperationFactory(factory)

            assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        }
    }

    @Test
    fun `each operation factory rejects a descriptor for another entity`() {
        for (factory in listOf("create", "createMany", "update", "delete", "deleteMany")) {
            val result = compileOperationFactory(factory, mappingEntity = "Other")

            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(result.messages.contains("type mismatch", ignoreCase = true), result.messages)
        }
    }

    @Test
    fun `update operation factory rejects rule input conversion for another entity state`() {
        val result = compileOperationFactory("update", ruleInputState = "OtherState")

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        assertTrue(result.messages.contains("type mismatch", ignoreCase = true), result.messages)
    }

    @Test
    fun `generated candidates implement the marker including id-only and colliding entity names`() {
        val schemas = listOf(Widget(), WriteCandidate())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent")
            .generate(schemas.map { SchemaInput(it) })
            .toCompileTestSources()
        val result = compile(
            generated + SourceFile.kotlin(
                "GeneratedCandidateBounds.kt",
                """
                package com.example.app

                import com.example.ent.Widget
                import com.example.ent.WidgetWriteCandidate
                import com.example.ent.WriteCandidate
                import com.example.ent.WriteCandidateWriteCandidate
                import entkt.runtime.mutation.WriteCandidate as Candidate

                fun widget(value: WidgetWriteCandidate): Candidate<Widget> = value
                fun idOnly(value: WriteCandidateWriteCandidate): Candidate<WriteCandidate> = value
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
