package entkt.migrations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * [normalizeWhere] reconciles pg_get_expr's deparsed form with the
 * user-written form — but only for decoration *outside* string
 * literals. Literal content is semantic: a partial index's predicate
 * decides which rows uniqueness covers, so two predicates that differ
 * only inside quotes must never compare equal.
 */
class NormalizeWhereTest {

    @Test
    fun `deparsed decoration reconciles with the user-written form`() {
        assertEquals(normalizeWhere("status = 'active'"), normalizeWhere("(status = 'active'::text)"))
        assertEquals(normalizeWhere("active = true"), normalizeWhere("((active)::boolean = true)"))
        assertEquals(normalizeWhere("deleted_at IS NULL"), normalizeWhere("(deleted_at  IS  NULL)"))
        assertNull(normalizeWhere(null))
    }

    @Test
    fun `whitespace inside a literal is significant`() {
        assertNotEquals(normalizeWhere("status = 'in  progress'"), normalizeWhere("status = 'in progress'"))
        assertEquals("status = 'in  progress'", normalizeWhere("status =   'in  progress'"))
    }

    @Test
    fun `a cast-lookalike inside a literal is not a cast`() {
        assertNotEquals(normalizeWhere("kind = 'foo::text'"), normalizeWhere("kind = 'foo'"))
        assertEquals("kind = 'foo::text'", normalizeWhere("kind = 'foo::text'"))
        assertEquals("note = '(x)::y'", normalizeWhere("note = '(x)::y'"))
        // A real cast after the literal still strips.
        assertEquals("kind = 'foo::text'", normalizeWhere("(kind = 'foo::text'::text)"))
    }

    @Test
    fun `doubled-quote escapes stay part of one literal`() {
        assertEquals("name = 'it''s'", normalizeWhere("name = 'it''s'"))
        assertEquals("note = 'it''s::ok'", normalizeWhere("(note = 'it''s::ok'::text)"))
    }

    @Test
    fun `parens inside a literal do not confuse outer-paren stripping`() {
        assertEquals("status = '(active'", normalizeWhere("(status = '(active')"))
    }

    @Test
    fun `numeric literals in the expression are untouched by literal masking`() {
        assertEquals("count > 5 AND status = 'a'", normalizeWhere("((count)::integer > 5 AND status = 'a'::text)"))
    }
}
