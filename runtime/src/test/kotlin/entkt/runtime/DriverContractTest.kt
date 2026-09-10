package entkt.runtime

import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.IsolationLevel
import kotlin.reflect.KParameter
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the [DatabaseDriver] members that MUST stay abstract so hand-written
 * decorators (metrics, tracing, Java implementations) are forced to
 * forward them — a default body would let a manually-forwarding
 * wrapper silently drop the behavior. Kotlin `by`-delegating
 * wrappers forward everything either way; these pins protect
 * everyone else.
 *
 * `registerAll` established the doctrine (a `forEach(::register)`
 * default would dissolve the batch ordering guarantee);
 * `requireBindCapacity` follows it (a no-op default would silently
 * disable the pre-snapshot bind guard and let huge operands
 * materialize again).
 */
class DriverContractTest {

    private fun assertAbstract(name: String, why: String) {
        val member = DatabaseDriver::class.declaredMemberFunctions.first { it.name == name }
        assertTrue(member.isAbstract, "DatabaseDriver.$name must stay abstract: $why")
    }

    @Test
    fun `members that decorators must forward stay abstract`() {
        assertAbstract(
            "registerAll",
            "a forEach(::register) default would silently drop the batch ordering guarantee",
        )
        assertAbstract(
            "requireBindCapacity",
            "a no-op default would silently disable the pre-snapshot bind guard in manual decorators",
        )
        assertAbstract(
            "directToManyWindowCapability",
            "an EMULATED default would silently downgrade a native driver's per-parent windows " +
                "back to full-result overfetch in manual decorators",
        )
        assertAbstract(
            "queryDirectToMany",
            "it forwards as one unit with directToManyWindowCapability — a decorator forwarding " +
                "the capability but inheriting a throwing default would fail at the first native read",
        )
        assertAbstract(
            "withTransaction",
            "decorators must accept and forward the requested transaction isolation",
        )
    }

    @Test
    fun `one transaction entry point carries optional isolation and a trailing block`() {
        val members = DatabaseDriver::class.declaredMemberFunctions.filter { it.name == "withTransaction" }
        assertEquals(1, members.size, "a second entry point could bypass a transaction decorator")

        val parameters = members.single().parameters.filter { it.kind == KParameter.Kind.VALUE }
        assertEquals(listOf("isolation", "block"), parameters.map { it.name })
        val isolation = parameters.first()
        assertEquals(IsolationLevel::class, isolation.type.classifier)
        assertTrue(isolation.type.isMarkedNullable)
        assertTrue(isolation.isOptional, "existing calls must preserve the driver's default isolation")
    }
}
