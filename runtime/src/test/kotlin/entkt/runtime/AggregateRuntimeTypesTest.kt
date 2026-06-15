package entkt.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AggregateRuntimeTypesTest {

    @Test
    fun `RAW_AGGREGATE is a ReadOperation`() {
        assertEquals(ReadOperation.RAW_AGGREGATE, ReadOperation.valueOf("RAW_AGGREGATE"))
    }

    @Test
    fun `limit operations do not apply to RAW_AGGREGATE`() {
        // Like rawCount/rawExists, an aggregate must not be silently capped by a
        // limit-clamping interceptor — limitOpsApply gates that.
        assertFalse(limitOpsApply(ReadOperation.RAW_AGGREGATE))
    }

    @Test
    fun `a driver without aggregate support reports false and throws`() {
        assertFalse(NoopDriver.supportsAggregates())
        assertFailsWith<UnsupportedDriverCapabilityException> {
            NoopDriver.aggregate("t", AggregateFunction.COUNT, null, emptyList())
        }
    }
}
