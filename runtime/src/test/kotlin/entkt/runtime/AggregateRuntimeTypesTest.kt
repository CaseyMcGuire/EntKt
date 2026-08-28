package entkt.runtime

import entkt.runtime.driver.NoopDriver
import entkt.runtime.mutation.UnsupportedDriverCapabilityException
import entkt.runtime.query.AggregateFunction

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AggregateRuntimeTypesTest {

    @Test
    fun `a driver without aggregate support reports false and throws`() {
        assertFalse(NoopDriver.supportsAggregates())
        assertFailsWith<UnsupportedDriverCapabilityException> {
            NoopDriver.aggregate("t", AggregateFunction.COUNT, null, emptyList())
        }
    }
}
