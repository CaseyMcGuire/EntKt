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

    private class UpdateRuleInput : EntSchema("update_rule_inputs", clientName = "updateRuleInputs") {
        override fun id() = EntId.int()
    }

    private class DeleteRuleInput : EntSchema("delete_rule_inputs", clientName = "deleteRuleInputs") {
        override fun id() = EntId.int()
    }

    private fun mutationTypes(candidate: String): List<String> = listOf(
        "CreateMutationConverter<WidgetDraft, $candidate, Widget, BeforeCreate>",
        "CreateMutationOperation<EntRuleClient, WidgetDraft, $candidate, Widget, BeforeSave, BeforeCreate>",
        "CreateManyMutationOperation<EntRuleClient, WidgetDraft, $candidate, Widget, BeforeSave, BeforeCreate>",
        "DeleteMutationConverter<Widget, $candidate>",
        "DeleteMutationOperation<EntRuleClient, Widget, $candidate>",
        "DeleteManyMutationOperation<EntRuleClient, Widget, $candidate>",
        "DeleteRuleInput<Widget, $candidate>",
    ) + updateRuleInputTypes(candidate = candidate)

    private fun updateRuleInputTypes(
        updateInput: String = "UpdateInput",
        candidate: String = "WidgetCandidate",
    ): List<String> = listOf(
        "UpdateMutationAdapter<WidgetUpdateDraft, Widget, PendingEdges, $candidate, BeforeUpdate, $updateInput>",
        "UpdateMutationOperation<EntRuleClient, WidgetUpdateDraft, Widget, PendingEdges, $candidate, BeforeSave, BeforeUpdate, $updateInput>",
    )

    private fun compileTypes(types: List<String>): JvmCompilationResult = compileSources(
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
                import entkt.runtime.mutation.DeleteRuleInput
                import entkt.runtime.mutation.PreparedCreate
                import entkt.runtime.mutation.UpdateMutationDraft
                import entkt.runtime.mutation.UpdatePendingEdges
                import entkt.runtime.mutation.UpdateRuleInput
                import entkt.runtime.mutation.WriteCandidate
                import entkt.runtime.mutation.execution.*
                import entkt.runtime.rule.EntRuleClient

                data class Widget(override val id: Int) : EntEntity.IntId
                data class Other(override val id: Int) : EntEntity.IntId
                class WidgetDraft : CreateMutationDraft<Widget>
                class WidgetUpdateDraft : UpdateMutationDraft<Widget>
                class PendingEdges : UpdatePendingEdges<Widget>
                class WidgetCandidate : WriteCandidate<Widget>
                class OtherCandidate : WriteCandidate<Other>
                class UpdateInput : UpdateRuleInput<Widget>
                class OtherUpdateInput : UpdateRuleInput<Other>
                class BeforeSave : BeforeSaveHookState<Widget>
                class BeforeCreate : BeforeCreateHookState<Widget>
                class BeforeUpdate : BeforeUpdateHookState<Widget>

                ${types.mapIndexed { index, type -> "fun accept$index(value: $type) {}" }.joinToString("\n")}
                """.trimIndent(),
            ),
        ),
    )

    @Test
    fun `mutation types accept candidates for their entity and marked rule clients`() {
        val result = compileTypes(
            mutationTypes("WidgetCandidate") + listOf(
                "PreparedCreate<WidgetCandidate>",
                "PreparedUpdate<UpdateInput, WidgetCandidate>",
                "UpdatePreparation<UpdateInput, WidgetCandidate>",
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
            "PreparedUpdate<UpdateInput, String>",
            "UpdatePreparation<UpdateInput, String>",
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

    @Test
    fun `update types reject rule inputs for a different entity`() {
        for (type in updateRuleInputTypes(updateInput = "OtherUpdateInput")) {
            assertUpdateRuleInputBoundFailure(type)
        }
    }

    @Test
    fun `update types reject rule inputs that do not implement the marker`() {
        val unmarked = listOf("String", "DeleteRuleInput<Widget, WidgetCandidate>")
        for (type in unmarked.flatMap { updateRuleInputTypes(updateInput = it) }) {
            assertUpdateRuleInputBoundFailure(type)
        }
    }

    private fun assertUpdateRuleInputBoundFailure(type: String) {
        val result = compileTypes(listOf(type))

        assertEquals(
            KotlinCompilation.ExitCode.COMPILATION_ERROR,
            result.exitCode,
            "Expected $type to reject the rule input:\n${result.messages}",
        )
        assertTrue(
            result.messages.contains("bound", ignoreCase = true) && result.messages.contains("UpdateRuleInput"),
            "Expected an UpdateRuleInput bound diagnostic for $type:\n${result.messages}",
        )
    }

    private fun compileOperationFactory(
        factory: String,
        mappingEntity: String = "Widget",
        updateAdapterType: String = "CombinedUpdateAdapter",
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
                    adapter = updateAdapter,
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
                    beforeDelete = emptyList(), afterDelete = emptyList(),
                )
            """.trimIndent()
            "deleteMany" -> """
                buildDeleteManyMutationOperation(
                    entity, deleteConverter, privacy, validation,
                    driver = driver, readExecutionHost = readHost,
                    beforeDelete = emptyList(), afterDelete = emptyList(),
                )
            """.trimIndent()
            else -> error("Unknown factory: $factory")
        }

        val resultType = when (factory) {
            "create" -> "CreateMutationOperations<EntRuleClient, WidgetCreateDraft, Widget>"
            "update" -> "MutationOperation<EntRuleClient, UpdateMutationInput<WidgetUpdateDraft>, Widget>"
            "createMany" -> "CreateManyMutationOperation<EntRuleClient, WidgetCreateDraft, WidgetCandidate, Widget, BeforeSave, BeforeCreate>"
            "delete" -> "DeleteMutationOperation<EntRuleClient, Widget, WidgetCandidate>"
            else -> "DeleteManyMutationOperation<EntRuleClient, Widget, WidgetCandidate>"
        }

        return compileSources(
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
                    import entkt.runtime.rule.EntRuleClient
                    import entkt.runtime.validation.BatchValidationRule
                    import entkt.runtime.validation.ResolvedEntityValidationConfig

                    data class Widget(override val id: Int) : EntEntity.IntId
                    data class Other(override val id: Int) : EntEntity.IntId
                    class WidgetCandidate : WriteCandidate<Widget>
                    class UpdateInput : UpdateRuleInput<Widget>
                    class WidgetCreateDraft : CreateMutationDraft<Widget>
                    class WidgetUpdateDraft : UpdateMutationDraft<Widget>
                    class PendingEdges : UpdatePendingEdges<Widget>
                    class BeforeSave : BeforeSaveHookState<Widget>
                    class BeforeCreate : BeforeCreateHookState<Widget>
                    class BeforeUpdate : BeforeUpdateHookState<Widget>

                    abstract class CombinedCreateConverter :
                        CreateMutationConverter<WidgetCreateDraft, WidgetCandidate, Widget, BeforeCreate>,
                        CreateMutationHookStateConverter<WidgetCreateDraft, Widget, BeforeSave, BeforeCreate>

                    abstract class CombinedUpdateAdapter :
                        UpdateMutationAdapter<WidgetUpdateDraft, Widget, PendingEdges, WidgetCandidate, BeforeUpdate, UpdateInput>,
                        UpdateMutationHookStateConverter<WidgetUpdateDraft, Widget, PendingEdges, BeforeSave, BeforeUpdate>

                    fun bind(
                        entity: EntityDescriptor<$mappingEntity, *>,
                        privacy: ResolvedEntityPrivacyConfig<
                            Nothing, BatchPrivacyRule<EntRuleClient, WidgetCandidate>,
                            BatchPrivacyRule<EntRuleClient, UpdateInput>,
                            BatchPrivacyRule<EntRuleClient, DeleteRuleInput<Widget, WidgetCandidate>>,
                        >,
                        validation: ResolvedEntityValidationConfig<
                            BatchValidationRule<EntRuleClient, WidgetCandidate>,
                            BatchValidationRule<EntRuleClient, UpdateInput>,
                            BatchValidationRule<EntRuleClient, DeleteRuleInput<Widget, WidgetCandidate>>,
                        >,
                        runtime: MutationRuntime,
                        combinedCreateConverter: CombinedCreateConverter,
                        updateAdapter: $updateAdapterType,
                        createConverter: CreateMutationConverter<WidgetCreateDraft, WidgetCandidate, Widget, BeforeCreate>,
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
    fun `update factory requires one adapter implementing preparation and hook conversion`() {
        val incompleteAdapters = mapOf(
            "UpdateMutationAdapter<WidgetUpdateDraft, Widget, PendingEdges, WidgetCandidate, BeforeUpdate, UpdateInput>" to
                "UpdateMutationHookStateConverter",
            "UpdateMutationHookStateConverter<WidgetUpdateDraft, Widget, PendingEdges, BeforeSave, BeforeUpdate>" to
                "UpdateMutationAdapter",
        )
        for ((type, missingContract) in incompleteAdapters) {
            val result = compileOperationFactory("update", updateAdapterType = type)

            assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
            assertTrue(result.messages.contains(missingContract), result.messages)
        }
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
    fun `generated candidates implement the marker including id-only and colliding entity names`() {
        val schemas = listOf(Widget(), WriteCandidate())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent")
            .generate(schemas.map { SchemaInput(it) })
            .toCompileTestSources()
        val result = compileSources(
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

    @Test
    fun `generated rule inputs match their runtime types including colliding entity names`() {
        val schemas = listOf(Widget(), UpdateRuleInput(), DeleteRuleInput())
        val registry = schemas.associateBy { it::class }
        schemas.forEach { it.finalize(registry) }
        val generated = EntGenerator("com.example.ent")
            .generate(schemas.map { SchemaInput(it) })
            .toCompileTestSources()
        val result = compileSources(
            generated + SourceFile.kotlin(
                "GeneratedRuleInputBounds.kt",
                """
                package com.example.app

                import com.example.ent.DeleteRuleInput
                import com.example.ent.DeleteRuleInputDeleteRuleInput
                import com.example.ent.DeleteRuleInputWriteCandidate
                import com.example.ent.UpdateRuleInput
                import com.example.ent.UpdateRuleInputUpdateRuleInput
                import com.example.ent.Widget
                import com.example.ent.WidgetDeleteRuleInput
                import com.example.ent.WidgetUpdateRuleInput
                import com.example.ent.WidgetWriteCandidate
                import entkt.runtime.mutation.DeleteRuleInput as DeleteInput
                import entkt.runtime.mutation.UpdateRuleInput as UpdateInput

                fun update(value: WidgetUpdateRuleInput): UpdateInput<Widget> = value
                fun delete(value: WidgetDeleteRuleInput): DeleteInput<Widget, WidgetWriteCandidate> = value
                fun collidingUpdate(value: UpdateRuleInputUpdateRuleInput): UpdateInput<UpdateRuleInput> = value
                fun collidingDelete(
                    value: DeleteRuleInputDeleteRuleInput,
                ): DeleteInput<DeleteRuleInput, DeleteRuleInputWriteCandidate> = value
                """.trimIndent(),
            ),
        )

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }
}
