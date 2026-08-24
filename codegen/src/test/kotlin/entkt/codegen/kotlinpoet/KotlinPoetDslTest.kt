package entkt.codegen.kotlinpoet

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.STRING
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinPoetDslTest {

    @Test
    fun `builds a Kotlin file through nested declarations`() {
        val output = kotlinFile("com.example", "Greeting") {
            classType("Greeting") {
                primaryConstructor {
                    parameter("name", STRING)
                }
                property("name", STRING) {
                    initializer("name")
                }
                function("message", returnType = STRING) {
                    parameter("excited", BOOLEAN) {
                        defaultValue("false")
                    }
                    body {
                        beginControlFlow("return if (excited)")
                        statement("%S", "Hello!")
                        nextControlFlow("else")
                        statement("%S", "Hello")
                        endControlFlow()
                    }
                }
                companionObject {
                    property("DEFAULT_NAME", STRING) {
                        addModifiers(KModifier.PRIVATE, KModifier.CONST)
                        initializer("%S", "World")
                    }
                }
            }
        }.toString()

        assertEquals(
            """
            package com.example

            import kotlin.Boolean
            import kotlin.String

            public class Greeting(
              public val name: String,
            ) {
              public fun message(excited: Boolean = false): String = if (excited) {
                "Hello!"
              } else {
                "Hello"
              }

              public companion object {
                private const val DEFAULT_NAME: String = "World"
              }
            }
            """.trimIndent(),
            output.trimEnd(),
        )
    }

    @Test
    fun `standalone factories remain composable KotlinPoet values`() {
        val named = interfaceType("Named") {
            property("name", STRING)
        }
        val names = objectType("Names") {
            addSuperinterface(ClassName("com.example", "Named"))
        }

        val output = kotlinFile("com.example", "Names") {
            addType(named)
            addType(names)
        }.toString()

        assertEquals(
            """
            package com.example

            import kotlin.String

            public interface Named {
              public val name: String
            }

            public object Names : Named
            """.trimIndent(),
            output.trimEnd(),
        )
    }
}
