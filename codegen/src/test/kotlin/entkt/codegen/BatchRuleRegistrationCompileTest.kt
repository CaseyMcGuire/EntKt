@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchRuleRegistrationCompileTest {
    private class Widget : EntSchema("widgets", clientName = "widgets") {
        override fun id() = EntId.long()
        val name by string("name")
    }

    private fun compile(vararg application: SourceFile): JvmCompilationResult {
        val generated = EntGenerator("com.example.ent")
            .generate(listOf(SchemaInput(Widget())))
            .toCompileTestSources()
        return compileSources(generated + application)
    }

    private fun source(name: String, body: String): SourceFile = SourceFile.kotlin(
        "$name.kt",
        """
        package com.example.app

        import com.example.ent.*
        import entkt.runtime.privacy.*
        import entkt.runtime.validation.*
        import kotlin.test.assertEquals

        $body
        """.trimIndent(),
    )

    @Test
    fun `privacy accepts single multiple and spread batch rules in every phase`() {
        val result = compile(source("PrivacyVarargs", """
            fun register(
                scope: WidgetPrivacyScope,
                load: WidgetLoadBatchPrivacyRule,
                create: WidgetCreateBatchPrivacyRule,
                update: WidgetUpdateBatchPrivacyRule,
                delete: WidgetDeleteBatchPrivacyRule,
            ) {
                scope.load()
                scope.load(load)
                scope.load(load, load)
                scope.load(*arrayOf(load, load))
                scope.load(*emptyArray<WidgetLoadBatchPrivacyRule>())
                scope.create()
                scope.create(create)
                scope.create(create, create)
                scope.create(*arrayOf(create, create))
                scope.create(*emptyArray<WidgetCreateBatchPrivacyRule>())
                scope.update()
                scope.update(update)
                scope.update(update, update)
                scope.update(*arrayOf(update, update))
                scope.update(*emptyArray<WidgetUpdateBatchPrivacyRule>())
                scope.delete()
                scope.delete(delete)
                scope.delete(delete, delete)
                scope.delete(*arrayOf(delete, delete))
                scope.delete(*emptyArray<WidgetDeleteBatchPrivacyRule>())

                scope.load(
                    batchPrivacyRule { _, batch ->
                        batch.decideEach { if (it.name.isBlank()) PrivacyDecision.Deny("blank") else PrivacyDecision.Allow }
                    },
                    batchPrivacyRule { _, batch -> batch.decideEach { PrivacyDecision.Continue } },
                )
            }
        """.trimIndent()))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `validation accepts single multiple and spread batch rules in every phase`() {
        val result = compile(source("ValidationVarargs", """
            fun register(
                scope: WidgetValidationScope,
                create: WidgetCreateBatchValidationRule,
                update: WidgetUpdateBatchValidationRule,
                delete: WidgetDeleteBatchValidationRule,
            ) {
                scope.create()
                scope.create(create)
                scope.create(create, create)
                scope.create(*arrayOf(create, create))
                scope.create(*emptyArray<WidgetCreateBatchValidationRule>())
                scope.update()
                scope.update(update)
                scope.update(update, update)
                scope.update(*arrayOf(update, update))
                scope.update(*emptyArray<WidgetUpdateBatchValidationRule>())
                scope.delete()
                scope.delete(delete)
                scope.delete(delete, delete)
                scope.delete(*arrayOf(delete, delete))
                scope.delete(*emptyArray<WidgetDeleteBatchValidationRule>())

                scope.create(
                    batchValidationRule { _, batch ->
                        batch.decideEach { if (it.name.isBlank()) ValidationDecision.Invalid("blank") else ValidationDecision.Valid }
                    },
                    batchValidationRule { _, batch -> batch.decideEach { ValidationDecision.Valid } },
                )
            }
        """.trimIndent()))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `scalar constructors infer clients and lifecycle items through batch registration`() {
        val result = compile(source("ScalarInference", """
            fun privacy(scope: WidgetPrivacyScope) {
                scope.load(PrivacyRule { context, item ->
                    val client: ReadOnlyEntClient = context.client
                    val entity: Widget = item
                    PrivacyDecision.Allow
                })
                scope.create(PrivacyRule { context, item ->
                    val client: ReadOnlyEntClient = context.client
                    val candidate: WidgetWriteCandidate = item
                    PrivacyDecision.Allow
                })
                scope.update(PrivacyRule { context, item ->
                    val client: ReadOnlyEntClient = context.client
                    val input: WidgetUpdateRuleInput = item
                    PrivacyDecision.Allow
                })
                scope.delete(PrivacyRule { context, item ->
                    val client: ReadOnlyEntClient = context.client
                    val input: WidgetDeleteRuleInput = item
                    PrivacyDecision.Allow
                })
            }

            fun validation(scope: WidgetValidationScope) {
                scope.create(ValidationRule { context, item ->
                    val client: ReadOnlyEntClient = context.client
                    val candidate: WidgetWriteCandidate = item
                    ValidationDecision.Valid
                })
                scope.update(ValidationRule { context, item ->
                    val client: ReadOnlyEntClient = context.client
                    val input: WidgetUpdateRuleInput = item
                    ValidationDecision.Valid
                })
                scope.delete(ValidationRule { context, item ->
                    val client: ReadOnlyEntClient = context.client
                    val input: WidgetDeleteRuleInput = item
                    ValidationDecision.Valid
                })
            }
        """.trimIndent()))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `Java uses the same method names for scalar batch and mixed registrations`() {
        val result = compile(SourceFile.java(
            "BatchVarargsJava.java",
            """
            package com.example.app;

            import com.example.ent.*;
            import entkt.runtime.privacy.*;
            import entkt.runtime.validation.*;

            public final class BatchVarargsJava {
                public static void privacy(
                    WidgetPrivacyScope scope,
                    BatchPrivacyRule<ReadOnlyEntClient, Widget>[] load,
                    BatchPrivacyRule<ReadOnlyEntClient, WidgetWriteCandidate>[] create,
                    BatchPrivacyRule<ReadOnlyEntClient, WidgetUpdateRuleInput>[] update,
                    BatchPrivacyRule<ReadOnlyEntClient, WidgetDeleteRuleInput>[] delete
                ) {
                    scope.load();
                    scope.load(load[0]);
                    scope.load(load[0], load[1]);
                    scope.load(load);
                    scope.create(create[0], create[1]);
                    scope.create(create);
                    scope.update(update[0], update[1]);
                    scope.update(update);
                    scope.delete(delete[0], delete[1]);
                    scope.delete(delete);
                    PrivacyRule<ReadOnlyEntClient, Widget> scalar = (context, item) -> PrivacyDecision.Continue.INSTANCE;
                    scope.load(scalar);
                    scope.load(scalar, load[0], scalar);
                    scope.load(
                        (context, batch) -> batch.decideEach(item -> PrivacyDecision.Allow.INSTANCE),
                        (context, batch) -> batch.decideEach(item -> PrivacyDecision.Continue.INSTANCE)
                    );
                }

                public static void validation(
                    WidgetValidationScope scope,
                    BatchValidationRule<ReadOnlyEntClient, WidgetWriteCandidate>[] create,
                    BatchValidationRule<ReadOnlyEntClient, WidgetUpdateRuleInput>[] update,
                    BatchValidationRule<ReadOnlyEntClient, WidgetDeleteRuleInput>[] delete
                ) {
                    scope.create();
                    scope.create(create[0]);
                    scope.create(create[0], create[1]);
                    scope.create(create);
                    scope.update(update[0], update[1]);
                    scope.update(update);
                    scope.delete(delete[0], delete[1]);
                    scope.delete(delete);
                    ValidationRule<ReadOnlyEntClient, WidgetWriteCandidate> scalar = (context, item) -> ValidationDecision.Valid.INSTANCE;
                    scope.create(scalar);
                    scope.create(scalar, create[0], scalar);
                    scope.create(
                        (context, batch) -> batch.decideEach(item -> ValidationDecision.Valid.INSTANCE),
                        (context, batch) -> batch.decideEach(item -> ValidationDecision.Valid.INSTANCE)
                    );
                }
            }
            """.trimIndent(),
        ))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    }

    @Test
    fun `batch arrays retain lifecycle item type constraints`() {
        val result = compile(
            source("WrongPrivacyPhase", """
                fun invalid(scope: WidgetPrivacyScope, rule: WidgetUpdateBatchPrivacyRule) {
                    scope.create(*arrayOf(rule))
                }
            """.trimIndent()),
            source("WrongValidationPhase", """
                fun invalid(scope: WidgetValidationScope, rule: WidgetUpdateBatchValidationRule) {
                    scope.create(*arrayOf(rule))
                }
            """.trimIndent()),
        )

        assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
        for (name in listOf("WrongPrivacyPhase", "WrongValidationPhase")) {
            assertTrue(result.messages.lineSequence().any { it.startsWith("e:") && "$name.kt:" in it }, result.messages)
        }
    }

    @Test
    fun `mixed scalar and batch registrations preserve order duplicates and empty inputs`() {
        val result = compile(source("RegistrationOrder", """
            fun verifyRegistrationOrder() {
                val scalarPrivacy = WidgetLoadPrivacyRule { _, _ -> error("registration must not evaluate rules") }
                val firstPrivacy: WidgetLoadBatchPrivacyRule = batchPrivacyRule { _, _ -> error("not evaluated") }
                val secondPrivacy: WidgetLoadBatchPrivacyRule = batchPrivacyRule { _, _ -> error("not evaluated") }
                val privacyConfig = WidgetPrivacyConfig()
                WidgetPrivacyScope(privacyConfig).apply {
                    load(scalarPrivacy)
                    load(firstPrivacy, secondPrivacy)
                    load(*arrayOf(secondPrivacy, firstPrivacy))
                    load(firstPrivacy, scalarPrivacy)
                    load(scalarPrivacy, secondPrivacy, scalarPrivacy)
                    load(*emptyArray<WidgetLoadBatchPrivacyRule>())
                    load()
                }
                assertEquals(
                    listOf(
                        scalarPrivacy,
                        firstPrivacy, secondPrivacy,
                        secondPrivacy, firstPrivacy,
                        firstPrivacy, scalarPrivacy,
                        scalarPrivacy, secondPrivacy, scalarPrivacy,
                    ),
                    privacyConfig.loadRules,
                    "privacy registration order",
                )

                val scalarValidation = WidgetCreateValidationRule { _, _ -> error("registration must not evaluate rules") }
                val firstValidation: WidgetCreateBatchValidationRule = batchValidationRule { _, _ -> error("not evaluated") }
                val secondValidation: WidgetCreateBatchValidationRule = batchValidationRule { _, _ -> error("not evaluated") }
                val validationConfig = WidgetValidationConfig()
                WidgetValidationScope(validationConfig).apply {
                    create(scalarValidation)
                    create(firstValidation, secondValidation)
                    create(*arrayOf(secondValidation, firstValidation))
                    create(firstValidation, scalarValidation)
                    create(scalarValidation, secondValidation, scalarValidation)
                    create(*emptyArray<WidgetCreateBatchValidationRule>())
                    create()
                }
                assertEquals(
                    listOf(
                        scalarValidation,
                        firstValidation, secondValidation,
                        secondValidation, firstValidation,
                        firstValidation, scalarValidation,
                        scalarValidation, secondValidation, scalarValidation,
                    ),
                    validationConfig.createRules,
                    "validation registration order",
                )
            }
        """.trimIndent()))

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        try {
            result.classLoader.loadClass("com.example.app.RegistrationOrderKt")
                .getMethod("verifyRegistrationOrder").invoke(null)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}
