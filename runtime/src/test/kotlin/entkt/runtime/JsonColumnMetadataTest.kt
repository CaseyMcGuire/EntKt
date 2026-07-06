package entkt.runtime

import entkt.runtime.driver.JsonColumnMetadata
import entkt.runtime.driver.JsonMapperIds
import kotlinx.serialization.builtins.serializer
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The metadata invariant that backs the codec cross-check: the kotlinx
 * serializer is present exactly when the mapper id is kotlinx. Codegen
 * upholds this by construction; hand-built metadata must too.
 */
class JsonColumnMetadataTest {

    @Test
    fun `kotlinx mapper requires a serializer`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            JsonColumnMetadata(
                klass = String::class,
                kType = typeOf<String>(),
                typeName = "kotlin.String",
                mapper = JsonMapperIds.KOTLINX,
                kotlinxSerializer = null,
            )
        }
        assertTrue("requires kotlinxSerializer" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `non-kotlinx mappers must not carry a kotlinx serializer`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            JsonColumnMetadata(
                klass = String::class,
                kType = typeOf<String>(),
                typeName = "kotlin.String",
                mapper = JsonMapperIds.JACKSON,
                kotlinxSerializer = String.serializer(),
            )
        }
        assertTrue("must be null" in ex.message!!, ex.message ?: "")
    }

    @Test
    fun `both valid combinations construct`() {
        JsonColumnMetadata(
            klass = String::class,
            kType = typeOf<String>(),
            typeName = "kotlin.String",
            mapper = JsonMapperIds.KOTLINX,
            kotlinxSerializer = String.serializer(),
        )
        JsonColumnMetadata(
            klass = String::class,
            kType = typeOf<String>(),
            typeName = "kotlin.String",
            mapper = "moshi", // third-party ids are open by design
        )
    }
}
