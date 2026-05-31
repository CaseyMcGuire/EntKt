package entkt.query

import kotlin.test.Test
import kotlin.test.assertEquals

class ColumnOrderTest {

    private enum class Status { OPEN, CLOSED }

    @Test
    fun `ComparableColumn order matches asc and desc by direction`() {
        val age = ComparableColumn<Any, Int>("age")
        assertEquals(OrderField("age", OrderDirection.ASC), age.order(OrderDirection.ASC))
        assertEquals(OrderField("age", OrderDirection.DESC), age.order(OrderDirection.DESC))
        // order(dir) is exactly asc()/desc() picked at runtime.
        assertEquals(age.asc(), age.order(OrderDirection.ASC))
        assertEquals(age.desc(), age.order(OrderDirection.DESC))
    }

    @Test
    fun `EnumColumn order matches asc and desc by direction`() {
        val status = EnumColumn<Any, Status>("status")
        assertEquals(OrderField("status", OrderDirection.ASC), status.order(OrderDirection.ASC))
        assertEquals(OrderField("status", OrderDirection.DESC), status.order(OrderDirection.DESC))
        assertEquals(status.asc(), status.order(OrderDirection.ASC))
        assertEquals(status.desc(), status.order(OrderDirection.DESC))
    }
}
