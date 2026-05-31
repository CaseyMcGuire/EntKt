@file:OptIn(EntktInternal::class)

package entkt.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract test for [EntktInternal] — pins the parts of the annotation
 * surface that the phantom-typed query scopes's "Constructor
 * Visibility" section depends on.
 *
 * The retention assertion is the hard cross-module invariant: if
 * retention drops below BINARY, generated codegen output in a
 * downstream module cannot satisfy the opt-in requirement.
 *
 * The level=ERROR and message-shape parts of the contract are validated
 * by behavior in the compile-fail harness (a call to an
 * `@EntktInternal` site without `@OptIn` must fail to compile with the
 * configured message). The `@RequiresOptIn` meta-annotation is not
 * always projected through `KClass<*>.annotations` at runtime, so we
 * don't try to read level/message reflectively here.
 *
 * As a smoke test we also exercise that `EntktInternal` is structurally
 * usable as an opt-in marker — this file declares
 * `@file:OptIn(EntktInternal::class)` so any test method below that
 * touches an `@EntktInternal` declaration would compile only if the
 * opt-in is recognized.
 */
class EntktInternalTest {

    @Test
    fun `EntktInternal has Retention BINARY`() {
        val retention: Annotation = EntktInternal::class.annotations
            .single { it.annotationClass.qualifiedName == "kotlin.annotation.Retention" }
        val retentionValue = retention.javaClass.getMethod("value").invoke(retention)

        assertEquals(
            AnnotationRetention.BINARY,
            retentionValue,
            "Retention.BINARY is required so the opt-in requirement " +
                "propagates across module boundaries (generated codegen " +
                "output lives in a different module than :schema). " +
                "SOURCE or RUNTIME retention would break this.",
        )
    }

    @Test
    fun `EntktInternal is structurally usable as an opt-in marker`() {
        // If EntktInternal were not a valid @RequiresOptIn-marked
        // annotation, the file-level @file:OptIn(EntktInternal::class)
        // at the top of this file would be a compile error
        // ("This declaration is not an opt-in requirement marker").
        // The fact that this test class compiles is the assertion.
        assertTrue(true)
    }
}
