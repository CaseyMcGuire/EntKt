package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Group
import entkt.integrationtest.ent.User
import entkt.integrationtest.support.PostgresTestBase
import entkt.runtime.query.requireLoaded
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end Postgres coverage for nullable M2M traversal's null-skip semantics on
 * `throughEntity` M2M traversal. Junction is [Membership], which has
 * nullable `group_id` / `user_id` columns. Every form of
 * `Group.users` / `User.groups` traversal must skip rows where
 * either junction FK is NULL.
 *
 * Three distinct SQL lowering shapes — each verified independently
 * because a regression in one can't be hidden by another:
 *
 *  - **Query-chain** (this file's block): `GroupQuery.queryUsers()`
 *    and `UserQuery.queryGroups()`. Lowers to a chained query
 *    against the target entity with a junction-bridging predicate.
 *  - **Predicate**: `Group.users.exists()` /
 *    `Group.users.has { where(...) }` and the inverse. Lowers to
 *    `Predicate.HasEdge` / `Predicate.HasEdgeWith` EXISTS
 *    subqueries.
 *  - **Eager loading**: `loadUsers { ... }` /
 *    `loadGroups { ... }` requested inside the `query { ... }`
 *    block. Two-step driver call (junction fetch then target fetch).
 *
 * Coverage matrix per shape: forward direction (`Group → User`)
 * and inverse direction (`User → Group`) × null-source-skip and
 * null-target-skip.
 */
class ThroughEntityNullableM2MTraversalIntegrationTest : PostgresTestBase() {

    private lateinit var client: EntClient

    @BeforeTest
    fun setUp() {
        val driver = resetAndDriver()
        client = EntClient(driver)
    }

    // ──────────────────────────────────────────────────────────────
    // Query-chain traversal: queryUsers / queryGroups
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `forward queryUsers excludes targets reached via null-source junction rows`() {
        // Setup: two groups, one user. Two memberships:
        //   m1: (g1, u1) — normal link
        //   m2: (null, u1) — null source; must NOT link u1 to any group
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "G2" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u1.id; role = "orphan-source" }.save(testViewerContext).getOrThrow()

        // g1.queryUsers() — pulls u1 via m1.
        val usersInG1 = client.groups.query()
            .where(Group.id eq g1.id)
            .queryUsers()
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(listOf(u1.id), usersInG1.map { it.id })

        // g2.queryUsers() — m1 doesn't link to g2, m2 has null source
        // (doesn't link to anyone). Must be empty; a buggy lowering
        // that joined junction rows with NULL source might surface
        // u1 here.
        val usersInG2 = client.groups.query()
            .where(Group.id eq g2.id)
            .queryUsers()
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(emptyList(), usersInG2)
    }

    @Test
    fun `forward queryUsers excludes null-target junction rows on the target side`() {
        // Setup: one group, one user. Two memberships:
        //   m1: (g1, u1) — normal link
        //   m2: (g1, null) — null target; must NOT contribute a row
        //                    (and definitely must not crash with
        //                    NullPointerException on the target decode)
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = null; role = "orphan-target" }.save(testViewerContext).getOrThrow()

        val usersInG1 = client.groups.query()
            .where(Group.id eq g1.id)
            .queryUsers()
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(listOf(u1.id), usersInG1.map { it.id }, "null target must be skipped")
    }

    @Test
    fun `inverse queryGroups excludes groups reached via null-target junction rows`() {
        // Inverse direction: User.groups uses pair-swapped junction
        // refs (Membership::user, Membership::group). "Null source"
        // on the inverse direction = `user_id IS NULL` on the
        // junction (the source-side junction edge from User's
        // perspective). "Null target" on the inverse = `group_id IS NULL`.
        //
        // This test pins null-target-on-inverse: a membership with
        // null group_id must NOT surface as a group in u1.queryGroups().
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u1.id; role = "orphan-target-on-inverse" }.save(testViewerContext).getOrThrow()

        val groupsForU1 = client.users.query()
            .where(User.id eq u1.id)
            .queryGroups()
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(listOf(g1.id), groupsForU1.map { it.id })
    }

    @Test
    fun `inverse queryGroups excludes groups reached via null-source junction rows`() {
        // Inverse direction, null-source variant: a membership with
        // `user_id IS NULL` claims no source user, so it must NOT
        // surface in any user's `queryGroups()` results.
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "G2" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = null; role = "orphan-source-on-inverse" }.save(testViewerContext).getOrThrow()

        // u1 is only linked to g1 (via m1). m2's null source means
        // it doesn't link to any user — so g2 shouldn't show up.
        val groupsForU1 = client.users.query()
            .where(User.id eq u1.id)
            .queryGroups()
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(listOf(g1.id), groupsForU1.map { it.id })
        assertTrue(groupsForU1.none { it.id == g2.id }, "null-source membership must not link g2 to any user")
    }

    // ──────────────────────────────────────────────────────────────
    // Predicate traversal: EdgeRef.exists() / .has { }
    // ──────────────────────────────────────────────────────────────
    //
    // Predicate lowering walks the junction with an EXISTS subquery:
    //
    //   EXISTS (
    //     SELECT 1 FROM memberships AS j
    //     JOIN <target> AS t ON t.id = j.<targetCol>
    //     WHERE j.<sourceCol> = <source>.id
    //         [ AND <inner filter on t> ]
    //   )
    //
    // Two SQL-NULL safety nets:
    //   - `j.<sourceCol> = source.id` evaluates UNKNOWN when sourceCol IS NULL.
    //   - `t.id = j.<targetCol>` (the INNER JOIN) evaluates UNKNOWN when targetCol IS NULL.
    //
    // Both are pinned with separate tests: one with only a null-target
    // junction row (the strong "JOIN must drop null target" check), and
    // one with stray null-source junction rows to verify they don't
    // cause false-positive matches on unrelated parents.

    @Test
    fun `forward exists predicate skips groups whose only membership row has null target`() {
        // g1's only membership row is (g1, null). The WHERE clause
        // `j.group_id = g1.id` succeeds, but the JOIN `users.id = j.user_id`
        // must fail (= NULL → UNKNOWN), so users.exists() must NOT
        // match g1. A buggy lowering that omitted the JOIN to users
        // would let g1 leak in.
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = null; role = "orphan-target" }.save(testViewerContext).getOrThrow()

        val matched = client.groups.query()
            .where(Group.users.exists())
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(emptyList(), matched, "null-target junction row must not satisfy users.exists()")
    }

    @Test
    fun `forward exists predicate ignores stray null-source junction rows`() {
        // g1 has no real linkage; g2 has one real (g2, u1) link.
        // Stray (null, u1) and (null, u2) rows must NOT cause g1 to
        // appear in the result — the WHERE `j.group_id = source.id`
        // clause drops them via SQL three-valued logic.
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "G2" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val u2 = client.users.create { name = "U2"; email = "u2@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u1.id; role = "orphan-source-a" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u2.id; role = "orphan-source-b" }.save(testViewerContext).getOrThrow()

        val matched = client.groups.query()
            .where(Group.users.exists())
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(listOf(g2.id), matched.map { it.id })
    }

    @Test
    fun `forward has predicate with target filter skips null-target junction rows`() {
        // Equivalent to the forward-exists null-target test, but with a
        // target-side filter folded into the EXISTS subquery.
        // `users.has { where(User.name eq "U1") }` adds `AND t.name = 'U1'`
        // to the join. Even before the filter applies, the JOIN's
        // `t.id = j.user_id` already drops the null-target row.
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = null; role = "orphan-target" }.save(testViewerContext).getOrThrow()

        val matched = client.groups.query()
            .where(Group.users.has { where(User.name eq "U1") })
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(emptyList(), matched)
    }

    @Test
    fun `forward has predicate with target filter ignores stray null-source junction rows`() {
        // Same shape as the bare-exists null-source-stray test, but
        // with `has { where(User.name eq "U1") }` to exercise the
        // target-filtered EXISTS lowering. Only g2 (the group with a
        // real link to a user named "U1") should match.
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "G2" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u1.id; role = "orphan-source" }.save(testViewerContext).getOrThrow()

        val matched = client.groups.query()
            .where(Group.users.has { where(User.name eq "U1") })
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(listOf(g2.id), matched.map { it.id })
    }

    @Test
    fun `inverse exists predicate skips users whose only membership row has null target`() {
        // Inverse direction: the junction's source col (from User's
        // perspective) is `user_id`; target col is `group_id`. "Null
        // target" on inverse means `group_id IS NULL`, which fails the
        // JOIN to `groups`. u1's only membership is (null, u1), so
        // users.groups.exists() must NOT match u1.
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u1.id; role = "orphan-target-on-inverse" }.save(testViewerContext).getOrThrow()

        val matched = client.users.query()
            .where(User.groups.exists())
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(emptyList(), matched, "null-target-on-inverse must not satisfy groups.exists()")
    }

    @Test
    fun `inverse exists predicate ignores stray null-source junction rows`() {
        // "Null source" on inverse = `user_id IS NULL`. The WHERE clause
        // `j.user_id = users.id` drops such rows via three-valued logic;
        // they must not cause unrelated users to falsely satisfy
        // groups.exists().
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val u2 = client.users.create { name = "U2"; email = "u2@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u2.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = null; role = "orphan-source-on-inverse" }.save(testViewerContext).getOrThrow()

        val matched = client.users.query()
            .where(User.groups.exists())
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(listOf(u2.id), matched.map { it.id })
    }

    @Test
    fun `inverse has predicate with target filter skips null-target junction rows`() {
        // Inverse `groups.has { where(Group.name eq "G1") }` against a
        // user whose only membership has `group_id IS NULL`. The JOIN
        // to `groups` drops the row; the target filter is moot.
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u1.id; role = "orphan-target-on-inverse" }.save(testViewerContext).getOrThrow()

        val matched = client.users.query()
            .where(User.groups.has { where(Group.name eq "G1") })
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(emptyList(), matched)
    }

    @Test
    fun `inverse has predicate with target filter ignores stray null-source junction rows`() {
        // u2 has a real (g1, u2) link; u1 has no real link.
        // (g1, null) stray must not cause u1 to be returned.
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val u2 = client.users.create { name = "U2"; email = "u2@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u2.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = null; role = "orphan-source-on-inverse" }.save(testViewerContext).getOrThrow()

        val matched = client.users.query()
            .where(User.groups.has { where(Group.name eq "G1") })
            .all(testViewerContext)
            .getOrThrow()
        assertEquals(listOf(u2.id), matched.map { it.id })
    }

    // ──────────────────────────────────────────────────────────────
    // Eager loading: loadUsers / loadGroups
    // ──────────────────────────────────────────────────────────────
    //
    // Eager loading uses two driver calls:
    //   1. junction = SELECT … FROM memberships WHERE <sourceCol> IN (parentIds)
    //   2. target   = SELECT … FROM <target>     WHERE id           IN (targetIds)
    //
    // `load{Edge}` returns an `EdgeLoad<ParentQuery>` handle, so the
    // eager request is made inside the `query { ... }` block (the
    // handle is ignored; strict eager semantics apply).
    //
    // Two SQL-NULL safety nets, distinct from the predicate path:
    //   - Step 1's `IN (sourceIds)` returns UNKNOWN when sourceCol IS NULL,
    //     so null-source junction rows never enter the pipeline.
    //   - Step 2's `IN (targetIds)` excludes any null target id (and the
    //     codegen looks up `sourcesByTargetId[target.id]` only for
    //     non-null target ids), so null-target junction rows can't
    //     contribute a null entry to `entity.edges.<edge>`.

    @Test
    fun `forward loadUsers omits users contributed by null-source junction rows`() {
        // Real link (g1, u1); stray (null, u2) row that should not
        // contribute u2 to g1 (the (null, u2) junction is filtered
        // out at step 1's `group_id IN (g1.id)` IN-test because
        // group_id IS NULL).
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        val u2 = client.users.create { name = "U2"; email = "u2@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u2.id; role = "orphan-source" }.save(testViewerContext).getOrThrow()

        val loaded = client.groups.query {
            where(Group.id eq g1.id)
            loadUsers()
        }.all(testViewerContext).getOrThrow()
        assertEquals(1, loaded.size)
        val users = loaded.first().edges.users.requireLoaded()
        assertEquals(listOf(u1.id), users.map { it.id }, "u2 must not surface — null-source junction filtered out")
    }

    @Test
    fun `forward loadUsers does not include null target user from null-target junction rows`() {
        // g1 has a real (g1, u1) link AND a (g1, null) row. Step 2's
        // target query excludes null ids (SQL `IN (..., NULL, ...)`
        // never matches users.id), and the per-target grouping looks
        // up by target.id which is non-null, so the null-user_id
        // junction row contributes nothing.
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = null; role = "orphan-target" }.save(testViewerContext).getOrThrow()

        val loaded = client.groups.query {
            where(Group.id eq g1.id)
            loadUsers()
        }.all(testViewerContext).getOrThrow()
        assertEquals(1, loaded.size)
        val users = loaded.first().edges.users.requireLoaded()
        assertEquals(listOf(u1.id), users.map { it.id }, "null-target junction must contribute nothing — and must not crash")
    }

    @Test
    fun `inverse loadGroups omits groups contributed by null-source junction rows`() {
        // Inverse direction: User.groups uses pair-swapped junction
        // refs, so "source col" = user_id, "target col" = group_id.
        // Stray (g2, null) junction must not surface g2 in u1's
        // groups list (step 1's `user_id IN (u1.id)` drops null).
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val g2 = client.groups.create { name = "G2" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g2.id; userId = null; role = "orphan-source-on-inverse" }.save(testViewerContext).getOrThrow()

        val loaded = client.users.query {
            where(User.id eq u1.id)
            loadGroups()
        }.all(testViewerContext).getOrThrow()
        assertEquals(1, loaded.size)
        val groups = loaded.first().edges.groups.requireLoaded()
        assertEquals(listOf(g1.id), groups.map { it.id })
    }

    @Test
    fun `inverse loadGroups does not include null target group from null-target junction rows`() {
        // u1 has a real (g1, u1) link AND a (null, u1) row (null group
        // from User's perspective). The null-group_id row's target id
        // is null, so step 2's `id IN (g1.id, NULL)` matches only
        // g1, and the per-target lookup keyed on target.id (non-null)
        // excludes the null-group junction.
        val g1 = client.groups.create { name = "G1" }.saveAndLoad(testViewerContext).getOrThrow()
        val u1 = client.users.create { name = "U1"; email = "u1@example.com" }.saveAndLoad(testViewerContext).getOrThrow()
        client.memberships.create { groupId = g1.id; userId = u1.id; role = "linked" }.save(testViewerContext).getOrThrow()
        client.memberships.create { groupId = null; userId = u1.id; role = "orphan-target-on-inverse" }.save(testViewerContext).getOrThrow()

        val loaded = client.users.query {
            where(User.id eq u1.id)
            loadGroups()
        }.all(testViewerContext).getOrThrow()
        assertEquals(1, loaded.size)
        val groups = loaded.first().edges.groups.requireLoaded()
        assertEquals(listOf(g1.id), groups.map { it.id }, "null-target-on-inverse must contribute nothing")
    }
}
