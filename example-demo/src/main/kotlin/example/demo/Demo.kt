package example.demo

import entkt.runtime.InMemoryDriver
import example.ent.EntClient
import example.ent.Post
import example.ent.User
import java.time.Instant

/**
 * Demonstrates the full entkt API against the example schemas, running
 * on the in-process [InMemoryDriver]. Every builder call in here
 * actually hits the driver — `create().save()` inserts rows,
 * `update(entity).save()` writes them back, `query().all()` filters
 * and returns typed entities, and edge predicates traverse through
 * the registered schema metadata.
 *
 * Production callers swap `InMemoryDriver()` for a real (SQL, HTTP,
 * whatever) driver without touching the generated code or this demo's
 * shape. There are no static entry points to intercept — every
 * operation flows through `client.users` / `client.posts` / etc., so
 * wiring in tests is a one-line constructor swap.
 *
 * Run with: ./gradlew :example-demo:run
 */
fun main() {
    fun banner(title: String) {
        println("=".repeat(60))
        println(" $title")
        println("=".repeat(60))
    }

    val client = EntClient(InMemoryDriver())

    banner("entkt demo — running against InMemoryDriver")
    println("Every call below actually hits the driver.")
    println()

    // ---------- Create real rows ----------
    banner("client.users.create { ... }.save()")
    val alice = client.users.create {
        name = "Alice"
        email = "alice@example.com"
        age = 30
        active = true
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }.save()
    println("Persisted: $alice")

    val bob = client.users.create {
        name = "Bob"
        email = "bob@admin.example.com"
        age = 65
        active = true
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }.save()
    println("Persisted: $bob")
    println()

    // ---------- Update via the repo ----------
    banner("client.users.update(alice) { ... }.save()")
    val olderAlice = client.users.update(alice) {
        age = 31
        updatedAt = Instant.now()
    }.save()
    println("Updated: $olderAlice")
    println()

    // ---------- byId ----------
    banner("client.users.byId(alice.id)")
    val fetched = client.users.byId(alice.id)
    println("Fetched: $fetched")
    println()

    // ---------- Query with typed column refs ----------
    banner("client.users.query { where(...); orderBy(...) }.all()")
    val adults = client.users.query {
        where(User.age gte 18)
        orderBy(User.age.desc())
    }.all()
    println("Adults by age desc: ${adults.map { it.name to it.age }}")
    println()

    // ---------- Compound predicates ----------
    banner("compound: active AND (age >= 65 OR email hasSuffix '@admin.example.com')")
    val specialUsers = client.users.query {
        where(
            (User.active eq true) and
                ((User.age gte 65) or (User.email hasSuffix "@admin.example.com")),
        )
    }.all()
    println("Matches: ${specialUsers.map { it.name }}")
    println()

    // ---------- Create posts linked to users ----------
    banner("client.posts.create { ... author = alice ... }.save()")
    val helloPost = client.posts.create {
        title = "Hello from Alice"
        body = "First post!"
        published = true
        author = olderAlice
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }.save()
    println("Persisted: $helloPost")

    val draftPost = client.posts.create {
        title = "Draft"
        body = "WIP"
        published = false
        author = bob
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }.save()
    println("Persisted: $draftPost")
    println()

    // ---------- Edge predicate: users with a published post ----------
    banner("client.users.query { where(User.posts.has { published eq true }) }.all()")
    val authorsWithPublished = client.users.query {
        where(User.posts.has { where(Post.published eq true) })
    }.all()
    println("Authors with published posts: ${authorsWithPublished.map { it.name }}")
    println()

    // ---------- Traversal: users who are active, then their posts ----------
    banner("client.users.query { active eq true }.queryPosts().all()")
    val postsOfActiveUsers = client.users
        .query { where(User.active eq true) }
        .queryPosts()
        .all()
    println("Posts of active users: ${postsOfActiveUsers.map { it.title }}")
    println()

    // ---------- firstOrNull ----------
    banner("client.users.query { name eq 'Alice' }.firstOrNull()")
    val maybeAlice = client.users.query { where(User.name eq "Alice") }.firstOrNull()
    println("First match: $maybeAlice")
    println()

    banner("done")
    println("See example-demo/build/generated/entkt/example/ent/ for the generated sources.")
}
