package example.demo

import example.ent.Post
import example.ent.Tag
import example.ent.User
import java.time.Instant
import java.util.UUID

/**
 * Demonstrates the shape of the entkt-generated API against the example
 * schemas defined in `:example`.
 *
 * NOTE: this demo does NOT call `.save()` on any builder because there
 * is no database/runtime layer yet — generated `save()` methods bail
 * out via `TODO("ID generation")`. The demo just *constructs* the
 * create/update/query builders to illustrate the fluent API surface.
 *
 * Run with: ./gradlew :example-demo:run
 */
fun main() {
    fun banner(title: String) {
        println("=".repeat(60))
        println(" $title")
        println("=".repeat(60))
    }

    banner("entkt API demo")
    println("This demo builds generated create/update/query builders to show")
    println("the API shape. save() is never called — there is no runtime yet.")
    println()

    // ---------- Create builder ----------
    banner("User.create()")
    val newUser = User.create()
        .setName("Alice")
        .setEmail("alice@example.com")
        .setAge(30)
        .setActive(true)
        .setCreatedAt(Instant.now())
        .setUpdatedAt(Instant.now())
    println("Built: $newUser")
    println("(Calling .save() here would hit TODO(\"ID generation\"))")
    println()

    // ---------- Update builder via entity.update() ----------
    banner("existingUser.update()")
    // Construct a User instance directly to simulate one loaded from a DB.
    val existingUser = User(
        id = UUID.randomUUID(),
        name = "Alice",
        email = "alice@example.com",
        age = 30,
        active = true,
        createdAt = Instant.now().minusSeconds(3600),
        updatedAt = Instant.now().minusSeconds(3600),
    )
    val updatedUser = existingUser.update()
        .setAge(31)
        .setUpdatedAt(Instant.now())
    println("Started from: $existingUser")
    println("Update builder: $updatedUser")
    println("(setName / setEmail omitted — they fall back to the entity's value)")
    println()

    // ---------- Query builder ----------
    banner("User.query()")
    val activeUsers = User.query()
        .whereActiveEq(true)
        .whereAgeGte(18)
        .whereEmailHasSuffix("@example.com")
        .orderDesc("created_at")
        .limit(20)
        .offset(0)
    println("Query: $activeUsers")
    println()

    // ---------- Post with edge ----------
    banner("Post.create() with edge convenience setter")
    val post = Post.create()
        .setTitle("Hello entkt")
        .setBody("A minimal port of Ent for Kotlin")
        .setPublished(true)
        .setAuthor(existingUser) // convenience setter — writes existingUser.id
        .setCreatedAt(Instant.now())
        .setUpdatedAt(Instant.now())
    println("Built: $post")
    println("Note: setAuthor(User) is a convenience for setAuthorId(user.id)")
    println()

    // ---------- Edge-aware query ----------
    banner("Post.query() with edge predicates")
    val postsByAlice = Post.query()
        .whereAuthorIdEq(existingUser.id)
        .wherePublishedEq(true)
        .whereHasAuthor()          // alias -> IS_NOT_NULL on author_id
        .whereTitleContains("entkt")
        .orderDesc("created_at")
    println("Query: $postsByAlice")
    println()

    // ---------- Enum field ----------
    banner("Tag.create() with enum field")
    val tag = Tag.create()
        .setName("kotlin")
        .setCategory("LANGUAGE")
    println("Built: $tag")
    println()

    banner("done")
    println("See example-demo/build/generated/entkt/example/ent/ for the generated sources.")
}