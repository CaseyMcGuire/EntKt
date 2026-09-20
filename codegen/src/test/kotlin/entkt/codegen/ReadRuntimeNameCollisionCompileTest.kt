@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package entkt.codegen

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import entkt.schema.EntId
import entkt.schema.EntSchema
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadRuntimeNameCollisionCompileTest {
    private class Item : EntSchema("items", clientName = "entities") {
        override fun id() = EntId.long()
    }

    private class Record : EntSchema("records", clientName = "entity") {
        override fun id() = EntId.long()
    }

    private class Document : EntSchema("documents", clientName = "viewerContext") {
        override fun id() = EntId.long()
    }

    @Test
    fun `client names matching dispatcher parameters compile and reach their own privacy rules`() {
        val schemas = listOf(Item(), Record(), Document())
        val generated = EntGenerator("com.example.ent")
            .generate(schemas.map(::SchemaInput))
            .toCompileTestSources()
        val probe = SourceFile.kotlin(
            "ReadRuntimeNameCollisionProbe.kt",
            """
            @file:OptIn(entkt.query.EntktInternal::class)
            package com.example.app

            import com.example.ent.*
            import entkt.runtime.driver.NoopDriver
            import entkt.runtime.privacy.EntityPolicy
            import entkt.runtime.privacy.PrivacyDecision
            import entkt.runtime.privacy.Viewer
            import entkt.runtime.privacy.ViewerContext

            object ReadRuntimeNameCollisionProbe {
                @JvmStatic
                fun run(): String {
                    val viewer = ViewerContext(Viewer.User(7L))
                    val calls = mutableListOf<String>()
                    val client = EntClient(NoopDriver) {
                        policies {
                            entities(object : EntityPolicy<Item, ItemPolicyScope> {
                                override fun configure(scope: ItemPolicyScope) = scope.privacy {
                                    load(ItemLoadPrivacyRule { context, item ->
                                        check(context.viewerContext === viewer)
                                        calls += "item:" + item.id
                                        PrivacyDecision.Allow
                                    })
                                }
                            })
                            entity(object : EntityPolicy<Record, RecordPolicyScope> {
                                override fun configure(scope: RecordPolicyScope) = scope.privacy {
                                    load(RecordLoadPrivacyRule { context, record ->
                                        check(context.viewerContext === viewer)
                                        calls += "record:" + record.id
                                        PrivacyDecision.Deny("record denied")
                                    })
                                }
                            })
                            viewerContext(object : EntityPolicy<Document, DocumentPolicyScope> {
                                override fun configure(scope: DocumentPolicyScope) = scope.privacy {
                                    load(DocumentLoadPrivacyRule { context, document ->
                                        check(context.viewerContext === viewer)
                                        calls += "document:" + document.id
                                        PrivacyDecision.Allow
                                    })
                                }
                            })
                        }
                    }
                    check(client.isConfigured(ItemDescriptor))
                    check(client.isConfigured(RecordDescriptor))
                    check(client.isConfigured(DocumentDescriptor))

                    val item = Item(1L)
                    val record = Record(2L)
                    val document = Document(3L)
                    check(client.evaluate(ItemDescriptor, viewer, listOf(item)).allowedSubjects() == listOf(item))
                    val denied = client.evaluate(RecordDescriptor, viewer, listOf(record)).firstDeniedOrNull()
                    check(denied?.subject === record && denied.reason == "record denied")
                    check(client.evaluate(DocumentDescriptor, viewer, listOf(document)).allowedSubjects() == listOf(document))
                    check(calls == listOf("item:1", "record:2", "document:3"))
                    return "ok"
                }
            }
            """.trimIndent(),
        )
        val result = compileSources(generated + probe)

        assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
        val probeClass = result.classLoader.loadClass("com.example.app.ReadRuntimeNameCollisionProbe")
        assertEquals("ok", probeClass.getMethod("run").invoke(null))
    }
}
