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
        // varchar column promoted to text — the only cast pg_get_expr
        // adds to a column reference.
        assertEquals(normalizeWhere("v = 'a'"), normalizeWhere("((v)::text = 'a'::text)"))
        // numeric literal decorated with the type it was parsed as,
        // including a multi-word type name.
        assertEquals(normalizeWhere("f = 1"), normalizeWhere("(f = (1)::double precision)"))
        assertEquals(normalizeWhere("n > 5"), normalizeWhere("(n > (5)::numeric)"))
        assertEquals(normalizeWhere("deleted_at IS NULL"), normalizeWhere("(deleted_at  IS  NULL)"))
        assertNull(normalizeWhere(null))
    }

    @Test
    fun `a value-changing column cast is part of the predicate identity`() {
        // On a float column, score::integer = 1 covers a whole rounding
        // bucket that score = 1 does not — the two predicates select
        // different rows and must never compare equal.
        assertNotEquals(normalizeWhere("score::integer = 1"), normalizeWhere("score = 1"))
        // The SAME cast still reconciles across the user-written and
        // deparsed spellings, including type aliases.
        assertEquals(normalizeWhere("score::integer = 1"), normalizeWhere("((score)::integer = 1)"))
        assertEquals(normalizeWhere("f::int8 > 5"), normalizeWhere("((f)::bigint > 5)"))
        // A length-modified textual cast truncates; it is kept too —
        // and both spellings of it agree.
        assertNotEquals(normalizeWhere("v::varchar(5) = 'a'"), normalizeWhere("v = 'a'"))
        assertEquals(normalizeWhere("v::varchar(5) = 'a'"), normalizeWhere("((v)::character varying(5) = 'a')"))
    }

    @Test
    fun `numeric constants respelled by the deparser reconcile with the bare form`() {
        // pg_get_expr respells signed and wide numeric constants as
        // quoted literals with casts, and re-parenthesizes bare ones.
        assertEquals(normalizeWhere("x <> -5"), normalizeWhere("(x <> '-5'::integer)"))
        assertEquals(normalizeWhere("big = 5000000000"), normalizeWhere("(big = '5000000000'::bigint)"))
        assertEquals(normalizeWhere("x = -5"), normalizeWhere("x = (-5)"))
        assertEquals(normalizeWhere("d > 1000"), normalizeWhere("(d > ('1000'::numeric)::double precision)"))
        // A quoted string stays a string: v = '5' is not v = 5.
        assertNotEquals(normalizeWhere("v = '5'"), normalizeWhere("v = 5"))
    }

    @Test
    fun `cast type spellings are case-insensitive and typmod-tolerant`() {
        assertEquals(normalizeWhere("f = 1"), normalizeWhere("f = (1)::DOUBLE PRECISION"))
        assertEquals(normalizeWhere("ts::timestamptz > 'x'"), normalizeWhere("((ts)::TIMESTAMP WITH TIME ZONE > 'x')"))
        // Postgres places a precision typmod mid-name; the user's alias
        // spelling carries it at the end. Both canonicalize together.
        assertEquals(
            normalizeWhere("ts::timestamptz(0) > 'x'"),
            normalizeWhere("((ts)::timestamp(0) with time zone > 'x')"),
        )
    }

    @Test
    fun `integer to numeric promotion strips only with an integer-family source`() {
        // pg_get_expr promotes an integer column met by a decimal
        // literal: x > 0.5 deparses as ((x)::numeric > 0.5).
        val ints = mapOf("x" to "integer")
        assertEquals(normalizeWhere("x > 0.5", ints), normalizeWhere("((x)::numeric > 0.5)", ints))
        // On a float column the same cast rounds — kept.
        val floats = mapOf("x" to "double precision")
        assertNotEquals(normalizeWhere("x > 0.5", floats), normalizeWhere("((x)::numeric > 0.5)", floats))
    }

    @Test
    fun `chained casts converge to one canonical spelling`() {
        val types = mapOf("v" to "character varying(255)", "c" to "citext")
        // The inner textual promotion strips, then the outer cast
        // canonicalizes — both spellings of the chain agree.
        assertEquals(
            normalizeWhere("v::text::integer > 5", types),
            normalizeWhere("(((v)::text)::integer > 5)", types),
        )
        // A kept inner cast unwraps its parens instead.
        assertEquals(
            normalizeWhere("c::text::integer > 5", types),
            normalizeWhere("(((c)::text)::integer > 5)", types),
        )
        // Normalization is a fixed point: applying it twice changes nothing.
        val once = normalizeWhere("(((v)::text)::integer > 5)", types)
        assertEquals(once, normalizeWhere(once, types))
    }

    @Test
    fun `a cast on a function result is left intact`() {
        // Whitespace between a function name and its parens must not
        // let the argument be rewritten as a parenthesized column.
        assertEquals("upper (v)::text = 'A'", normalizeWhere("upper (v)::text = 'A'"))
        assertEquals("abs (5)::integer > 2", normalizeWhere("abs (5)::integer > 2"))
        // ...while a keyword before the paren is still a grouped column.
        assertEquals(normalizeWhere("a = 1 AND v = 'x'"), normalizeWhere("a = 1 AND (v)::text = 'x'"))
    }

    @Test
    fun `truncating textual targets are never stripped`() {
        // col::char is character(1) and truncates; ::name truncates at
        // 63 bytes. Neither is deparser decoration.
        assertNotEquals(normalizeWhere("(v)::char = 'a'"), normalizeWhere("v = 'a'"))
        assertNotEquals(normalizeWhere("(v)::name = 'a'"), normalizeWhere("v = 'a'"))
    }

    @Test
    fun `casts with typmods on literals change the value and are kept`() {
        // 'foobar'::varchar(3) is 'foo'; (1.55)::numeric(2,1) is 1.6.
        assertNotEquals(normalizeWhere("b = 'foobar'::varchar(3)"), normalizeWhere("b = 'foobar'"))
        assertNotEquals(normalizeWhere("f = (1.55)::numeric(2,1)"), normalizeWhere("f = 1.55"))
    }

    @Test
    fun `citext casts change case-sensitivity and are never stripped`() {
        val types = mapOf("c" to "citext", "v" to "character varying(255)", "t" to "text")
        // c::text flips a citext comparison to case-sensitive
        // (PostgreSQL documents this for citext) — with the column's
        // type known, the cast is part of the predicate identity.
        assertNotEquals(normalizeWhere("c::text = 'a'", types), normalizeWhere("c = 'a'", types))
        assertEquals(normalizeWhere("c::text = 'a'", types), normalizeWhere("((c)::text = 'a'::text)", types))
        // ...while the deparser's varchar → text promotion still strips.
        assertEquals(normalizeWhere("v = 'a'", types), normalizeWhere("((v)::text = 'a'::text)", types))
        // Casting TO citext flips the other way; kept even with no map.
        assertNotEquals(normalizeWhere("t::citext = 'a'"), normalizeWhere("t = 'a'"))
        assertNotEquals(normalizeWhere("t::citext = 'a'", types), normalizeWhere("t = 'a'", types))
        // A column the map doesn't know gets no benefit of the doubt.
        assertNotEquals(normalizeWhere("mystery::text = 'a'", types), normalizeWhere("mystery = 'a'", types))
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
        assertEquals("count::integer > 5 AND status = 'a'", normalizeWhere("((count)::integer > 5 AND status = 'a'::text)"))
    }
}
